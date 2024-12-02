package com.nguyenquynh;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.File;
import java.net.*;
import java.util.Enumeration;

public class VideoStreamingApp extends Application {
    private String selectedVideoPath = null;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Ứng dụng Phát Video LAN");

        // Tạo layout chính
        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        // Tạo các nút chọn vai trò
        Button serverButton = new Button("Máy chủ (Server)");
        Button clientButton = new Button("Máy khách (Client)");

        serverButton.setOnAction(e -> showServerConfig(primaryStage));
        clientButton.setOnAction(e -> showClientConfig(primaryStage));

        // Tùy chỉnh style cho buttons
        serverButton.setPrefWidth(200);
        clientButton.setPrefWidth(200);

        // Thêm các thành phần vào layout
        root.getChildren().addAll(
                new Label("Chọn vai trò của bạn:"),
                serverButton,
                clientButton
        );

        // Tạo scene
        Scene scene = new Scene(root, 400, 300);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void showServerConfig(Stage primaryStage) {
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        // Lấy danh sách IP của máy
        ComboBox<String> ipComboBox = new ComboBox<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        ipComboBox.getItems().add(addr.getHostAddress());
                    }
                }
            }
            // Thêm localhost
            ipComboBox.getItems().add("127.0.0.1");
        } catch (SocketException e) {
            e.printStackTrace();
        }

        if (!ipComboBox.getItems().isEmpty()) {
            ipComboBox.setValue(ipComboBox.getItems().get(0));
        }

        // Input port
        TextField portField = new TextField("4444");
        portField.setMaxWidth(200);

        // Nút chọn file
        Button chooseFileBtn = new Button("Chọn video để phát");
        Label fileLabel = new Label("Chưa chọn file");

        chooseFileBtn.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Chọn video");
            FileChooser.ExtensionFilter extFilter =
                    new FileChooser.ExtensionFilter("Video files", "*.mp4", "*.avi", "*.mkv");
            fileChooser.getExtensionFilters().add(extFilter);

            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) {
                selectedVideoPath = selectedFile.getAbsolutePath();
                fileLabel.setText(selectedFile.getName());
            }
        });

        // Nút bắt đầu server
        Button startButton = new Button("Bắt đầu phát");
        startButton.setOnAction(e -> {
            if (selectedVideoPath == null) {
                showAlert("Lỗi", "Vui lòng chọn file video");
                return;
            }

            try {
                int port = Integer.parseInt(portField.getText());
                String selectedIp = ipComboBox.getValue();
                VideoServer server = new VideoServer(selectedVideoPath, selectedIp, port);
                server.streamVideo();
            } catch (NumberFormatException ex) {
                showAlert("Lỗi", "Port không hợp lệ");
            } catch (Exception ex) {
                showAlert("Lỗi", "Không thể khởi động server: " + ex.getMessage());
            }
        });

        // Nút quay lại
        Button backButton = new Button("Quay lại");
        backButton.setOnAction(e -> start(primaryStage));

        // Thêm các thành phần vào layout
        root.getChildren().addAll(
                new Label("Cấu hình Server"),
                new Label("IP của bạn:"),
                ipComboBox,
                new Label("Port:"),
                portField,
                chooseFileBtn,
                fileLabel,
                startButton,
                backButton
        );

        Scene scene = new Scene(root, 400, 450);
        primaryStage.setScene(scene);
    }

    private void showClientConfig(Stage primaryStage) {
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        // Input IP của server
        TextField ipField = new TextField();
        ipField.setPromptText("Nhập IP của server");
        ipField.setMaxWidth(200);

        // Input port
        TextField portField = new TextField("4444");
        portField.setMaxWidth(200);

        // Nút kết nối
        Button connectButton = new Button("Kết nối");
        connectButton.setOnAction(e -> {
            try {
                String ip = ipField.getText().trim();
                int port = Integer.parseInt(portField.getText().trim());

                if (ip.isEmpty()) {
                    showAlert("Lỗi", "Vui lòng nhập IP của server");
                    return;
                }

                // Validate IP
                try {
                    InetAddress.getByName(ip);
                } catch (UnknownHostException ex) {
                    showAlert("Lỗi", "IP không hợp lệ");
                    return;
                }

                // Khởi động client
                VideoClient client = new VideoClient(ip, port);
                client.start(new Stage());
                primaryStage.close();

            } catch (NumberFormatException ex) {
                showAlert("Lỗi", "Port không hợp lệ");
            } catch (Exception ex) {
                showAlert("Lỗi", "Không thể kết nối: " + ex.getMessage());
            }
        });

        // Nút quay lại
        Button backButton = new Button("Quay lại");
        backButton.setOnAction(e -> start(primaryStage));

        // Thêm các thành phần vào layout
        root.getChildren().addAll(
                new Label("Kết nối tới Server"),
                new Label("IP của Server:"),
                ipField,
                new Label("Port:"),
                portField,
                connectButton,
                backButton
        );

        Scene scene = new Scene(root, 400, 350);
        primaryStage.setScene(scene);
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}