package com.nguyenquynh;

import org.bytedeco.javacv.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.ByteBuffer;

public class VideoClient extends Application {
    private static final int BUFFER_SIZE = 65535;
    private static final int CONTROL_PORT = 4445;

    private final String serverIp;
    private final int serverPort;

    private MulticastSocket socket;
    private DatagramSocket controlSocket;
    private ImageView imageView;
    private Map<Integer, List<byte[]>> frameBuffers;
    private Map<Integer, boolean[]> receivedPackets;
    private Label statusLabel;
    private volatile boolean isPlaying = true;
    private Stage primaryStage;
    private boolean isInitialized = false;

    public VideoClient(String serverIp, int serverPort) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        try {
            setupUI(stage);
            initializeNetworking();
            startReceiving();
            isInitialized = true;
        } catch (Exception e) {
            showError("Lỗi Kết Nối", "Không thể kết nối tới server: " + e.getMessage());
        }
    }

    private void setupUI(Stage stage) {
        // Video view
        imageView = new ImageView();
        imageView.setFitWidth(640);
        imageView.setFitHeight(480);
        imageView.setPreserveRatio(true);

        // Controls
        Button stopButton = new Button("Dừng");
        statusLabel = new Label("Trạng thái: Đang kết nối...");

        // Control actions
        stopButton.setOnAction(e -> stop());

        // Layout
        HBox controls = new HBox(10);
        controls.setPadding(new Insets(10));
        controls.getChildren().addAll(stopButton, statusLabel);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.getChildren().addAll(imageView, controls);
        VBox.setVgrow(imageView, Priority.ALWAYS);

        Scene scene = new Scene(root);
        stage.setTitle("Video Client - Kết nối tới " + serverIp);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> cleanup());
        stage.show();
    }

    private void initializeNetworking() throws IOException {
        try {
            // Kiểm tra và xác thực IP server
            InetAddress serverAddr = InetAddress.getByName(serverIp);
            System.out.println("Đang kết nối tới server: " + serverAddr.getHostAddress());

            // Khởi tạo socket điều khiển trước
            controlSocket = new DatagramSocket();

            // Tìm network interface phù hợp
            NetworkInterface networkInterface = findNetworkInterface();
            if (networkInterface == null) {
                throw new IOException("Không tìm thấy network interface phù hợp");
            }

            // In thông tin interface tìm được
            System.out.println("Sử dụng network interface: " + networkInterface.getDisplayName());
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    System.out.println("Local IP: " + addr.getHostAddress());
                }
            }

            // Khởi tạo multicast socket
            socket = new MulticastSocket(serverPort);
            socket.setReuseAddress(true);
            socket.setNetworkInterface(networkInterface);

            // Join multicast group
            InetAddress groupAddr = InetAddress.getByName("224.0.0.1"); // Địa chỉ multicast tiêu chuẩn
            socket.joinGroup(groupAddr);

            // Khởi tạo buffers
            frameBuffers = new HashMap<>();
            receivedPackets = new HashMap<>();

            // Bắt đầu lắng nghe trạng thái
            setupStatusListener(networkInterface);

            // Gửi thông báo kết nối
            sendControlCommand("CONNECT");

            System.out.println("Kết nối thành công");
            System.out.println("Multicast group: 224.0.0.1");
            System.out.println("Port: " + serverPort);

        } catch (Exception e) {
            cleanup();
            throw new IOException("Lỗi khởi tạo kết nối: " + e.getMessage(), e);
        }
    }

    private NetworkInterface findNetworkInterface() {
        try {
            if (serverIp.equals("127.0.0.1") || serverIp.equals("localhost")) {
                return NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
            }

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isUp() && !nif.isLoopback()) {
                    Enumeration<InetAddress> addresses = nif.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            return nif;
                        }
                    }
                }
            }

            // Nếu không tìm được interface phù hợp, dùng interface đầu tiên
            return NetworkInterface.getByIndex(0);

        } catch (SocketException e) {
            System.err.println("Lỗi tìm network interface: " + e.getMessage());
            return null;
        }
    }

    private void setupStatusListener(NetworkInterface networkInterface) throws IOException {
        MulticastSocket statusSocket = new MulticastSocket(serverPort + 2);
        statusSocket.setReuseAddress(true);
        statusSocket.setNetworkInterface(networkInterface);

        InetAddress groupAddr = InetAddress.getByName("224.0.0.1");
        statusSocket.joinGroup(groupAddr);

        Thread statusThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    statusSocket.receive(packet);

                    String status = new String(packet.getData(), 0, packet.getLength());
                    updateStatus(status);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() ->
                            showError("Lỗi Kết Nối", "Mất kết nối với server: " + e.getMessage())
                    );
                }
            } finally {
                statusSocket.close();
            }
        });
        statusThread.setDaemon(true);
        statusThread.start();
    }

    private void startReceiving() {
        Thread receiveThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    if (!isPlaying) continue;

                    processPacket(packet);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() ->
                            showError("Lỗi Nhận Dữ Liệu", "Lỗi nhận video: " + e.getMessage())
                    );
                }
            }
        });
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private void processPacket(DatagramPacket packet) {
        try {
            ByteBuffer headerBuffer = ByteBuffer.wrap(packet.getData(), 0, 12);
            int frameNumber = headerBuffer.getInt();
            int packetNumber = headerBuffer.getInt();
            int totalPackets = headerBuffer.getInt();

            byte[] packetData = new byte[packet.getLength() - 12];
            System.arraycopy(packet.getData(), 12, packetData, 0, packetData.length);

            frameBuffers.putIfAbsent(frameNumber, new ArrayList<>(Collections.nCopies(totalPackets, null)));
            receivedPackets.putIfAbsent(frameNumber, new boolean[totalPackets]);

            List<byte[]> frameBuffer = frameBuffers.get(frameNumber);
            boolean[] received = receivedPackets.get(frameNumber);

            frameBuffer.set(packetNumber, packetData);
            received[packetNumber] = true;

            if (checkFrameComplete(frameNumber)) {
                processFrame(frameNumber);
            }
        } catch (Exception e) {
            System.err.println("Lỗi xử lý gói tin: " + e.getMessage());
        }
    }

    private boolean checkFrameComplete(int frameNumber) {
        boolean[] received = receivedPackets.get(frameNumber);
        for (boolean b : received) {
            if (!b) return false;
        }
        return true;
    }

    private void processFrame(int frameNumber) {
        try {
            List<byte[]> frameBuffer = frameBuffers.get(frameNumber);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] packetData : frameBuffer) {
                if (packetData != null) {
                    baos.write(packetData);
                }
            }

            byte[] frameData = baos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(frameData);
            Image image = new Image(bais);

            Platform.runLater(() -> imageView.setImage(image));

            // Cleanup processed frame
            frameBuffers.remove(frameNumber);
            receivedPackets.remove(frameNumber);

        } catch (Exception e) {
            System.err.println("Lỗi xử lý frame " + frameNumber + ": " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        Platform.runLater(() -> {
            switch (status) {
                case "PLAYING":
                    statusLabel.setText("Trạng thái: Đang phát");
                    isPlaying = true;
                    break;
                case "PAUSED":
                    statusLabel.setText("Trạng thái: Tạm dừng");
                    isPlaying = false;
                    break;
                case "STOPPED":
                    cleanup();
                    Platform.exit();
                    break;
            }
        });
    }

    public void stop() {
        cleanup();
        Platform.exit();
    }

    private void sendControlCommand(String command) throws IOException {
        if (controlSocket != null && !controlSocket.isClosed()) {
            byte[] data = command.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName(serverIp),
                    CONTROL_PORT
            );
            controlSocket.send(packet);
        }
    }

    private void cleanup() {
        if (isInitialized) {
            try {
                if (socket != null && !socket.isClosed()) {
                    try {
                        sendControlCommand("DISCONNECT");
                        InetAddress groupAddr = InetAddress.getByName("224.0.0.1");
                        socket.leaveGroup(groupAddr);
                    } catch (Exception e) {
                        System.err.println("Lỗi khi rời group: " + e.getMessage());
                    } finally {
                        socket.close();
                    }
                }
                if (controlSocket != null && !controlSocket.isClosed()) {
                    controlSocket.close();
                }
            } catch (Exception e) {
                System.err.println("Lỗi khi dọn dẹp resources: " + e.getMessage());
            }
        }
    }

    private void showError(String title, String message) {
        if (Platform.isFxApplicationThread()) {
            showErrorDialog(title, message);
        } else {
            Platform.runLater(() -> showErrorDialog(title, message));
        }
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();

        // Đóng stage nếu đang hiển thị
        if (primaryStage != null && primaryStage.isShowing()) {
            primaryStage.close();
        }

        // Đảm bảo ứng dụng được dọn dẹp đúng cách
        cleanup();
        Platform.exit();
    }
}