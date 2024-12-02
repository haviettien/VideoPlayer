package com.nguyenquynh;

import org.bytedeco.javacv.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

public class VideoServer {
    private static final int PACKET_SIZE = 1400;

    private final String videoPath;
    private final String serverIp;
    private final int port;
    private final AtomicBoolean isRunning;
    private final AtomicBoolean isPaused;

    private DatagramSocket socket;
    private DatagramSocket controlSocket;
    private FFmpegFrameGrabber grabber;
    private ImageView previewView;
    private Button playPauseButton;
    private Button stopButton;
    private Stage serverStage;
    private Label statusLabel;
    private Label connectedClientsLabel;
    private int connectedClients = 0;
    private volatile boolean isPreviewUpdating = false;

    static {
        // Tắt log không cần thiết của FFmpeg
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    public VideoServer(String videoPath, String serverIp, int port) {
        this.videoPath = videoPath;
        this.serverIp = serverIp;
        this.port = port;
        this.isRunning = new AtomicBoolean(true);
        this.isPaused = new AtomicBoolean(false);
        setupServerWindow();
    }

    private void setupServerWindow() {
        Platform.runLater(() -> {
            serverStage = new Stage();
            serverStage.setTitle("Bảng Điều Khiển Máy Chủ");

            // Lấy kích thước màn hình
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            double maxWidth = screenBounds.getWidth() * 0.8;
            double maxHeight = screenBounds.getHeight() * 0.8;

            // Khởi tạo preview
            previewView = new ImageView();
            previewView.setPreserveRatio(true);

            // Lấy kích thước video và thiết lập preview
            Thread videoSizeThread = new Thread(() -> {
                try {
                    FFmpegFrameGrabber tempGrabber = new FFmpegFrameGrabber(videoPath);
                    tempGrabber.start();

                    double videoWidth = tempGrabber.getImageWidth();
                    double videoHeight = tempGrabber.getImageHeight();

                    tempGrabber.stop();
                    tempGrabber.release();

                    double scaleFactor = Math.min(
                            maxWidth / videoWidth,
                            maxHeight / videoHeight
                    );

                    Platform.runLater(() -> {
                        previewView.setFitWidth(videoWidth * scaleFactor);
                        previewView.setFitHeight(videoHeight * scaleFactor);

                        // Điều chỉnh kích thước cửa sổ
                        double windowWidth = videoWidth * scaleFactor + 40;
                        double windowHeight = videoHeight * scaleFactor + 150;

                        serverStage.setMinWidth(windowWidth);
                        serverStage.setMinHeight(windowHeight);
                        serverStage.setWidth(windowWidth);
                        serverStage.setHeight(windowHeight);
                        serverStage.centerOnScreen();
                    });

                } catch (Exception e) {
                    Platform.runLater(() -> {
                        previewView.setFitWidth(640);
                        previewView.setFitHeight(360);
                        serverStage.setWidth(680);
                        serverStage.setHeight(510);
                        serverStage.centerOnScreen();
                    });
                }
            });
            videoSizeThread.setDaemon(true);
            videoSizeThread.start();

            // Controls
            playPauseButton = new Button("Tạm Dừng");
            stopButton = new Button("Dừng");
            statusLabel = new Label("Trạng thái: Đang khởi động...");
            connectedClientsLabel = new Label("Số người xem: 0");

            playPauseButton.setOnAction(e -> togglePlayPause());
            stopButton.setOnAction(e -> stopServer());

            HBox controls = new HBox(10);
            controls.setAlignment(Pos.CENTER);
            controls.getChildren().addAll(playPauseButton, stopButton);

            VBox root = new VBox(10);
            root.setPadding(new Insets(10));
            root.setAlignment(Pos.CENTER);
            root.getChildren().addAll(
                    previewView,
                    controls,
                    statusLabel,
                    connectedClientsLabel
            );

            Scene scene = new Scene(root);
            serverStage.setScene(scene);
            serverStage.setOnCloseRequest(e -> {
                e.consume();
                stopServer();
            });
            serverStage.show();
        });
    }

    public void streamVideo() {
        Thread streamThread = new Thread(() -> {
            try {
                initializeNetwork();
                initializeVideo();
                startControlThread();

                Frame frame;
                Java2DFrameConverter converter = new Java2DFrameConverter();
                int frameNumber = 0;
                long lastPreviewUpdate = 0;
                final long PREVIEW_UPDATE_INTERVAL = 100;

                updateStatus("Đang phát");

                while (isRunning.get()) {
                    if (isPaused.get()) {
                        Thread.sleep(50);
                        continue;
                    }

                    frame = grabber.grab();
                    if (frame == null) {
                        grabber.setTimestamp(0);
                        continue;
                    }

                    if (frame.image != null) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastPreviewUpdate >= PREVIEW_UPDATE_INTERVAL) {
                            updatePreview(frame, converter);
                            lastPreviewUpdate = currentTime;
                        }

                        sendFrame(frame, frameNumber++, converter);

                        Thread.sleep(Math.max(1, (long)(1000/grabber.getFrameRate())));
                    }
                }
            } catch (Exception e) {
                updateStatus("Lỗi: " + e.getMessage());
                e.printStackTrace();
            } finally {
                cleanup();
            }
        });

        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void initializeNetwork() throws IOException {
        try {
            // Khởi tạo socket cho video streaming
            socket = new DatagramSocket();

            // Khởi tạo socket cho điều khiển
            controlSocket = new DatagramSocket(port + 1);

            System.out.println("Server đang chạy:");
            System.out.println("Server IP: " + serverIp);
            System.out.println("Multicast Address: 224.0.0.1");
            System.out.println("Port: " + port);
            System.out.println("Control Port: " + (port + 1));

            updateStatus("Đã khởi tạo kết nối mạng");
        } catch (IOException e) {
            throw new IOException("Không thể khởi tạo kết nối mạng: " + e.getMessage(), e);
        }
    }

    private void initializeVideo() throws FFmpegFrameGrabber.Exception {
        File videoFile = new File(videoPath);
        if (!videoFile.exists() || !videoFile.canRead()) {
            throw new FFmpegFrameGrabber.Exception("Không thể truy cập file video: " + videoPath);
        }

        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setOption("threads", "auto");
        grabber.setOption("preset", "ultrafast");
        grabber.setVideoCodec(avcodec.AV_CODEC_ID_H264);

        grabber.start();
        updateStatus("Đã khởi tạo video");
    }

    private void startControlThread() {
        Thread controlThread = new Thread(() -> {
            try {
                while (isRunning.get()) {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    controlSocket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength());
                    handleControlMessage(message, packet.getAddress());
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    System.err.println("Lỗi nhận lệnh điều khiển: " + e.getMessage());
                }
            }
        });
        controlThread.setDaemon(true);
        controlThread.start();
    }

    private void handleControlMessage(String message, InetAddress clientAddress) {
        switch (message) {
            case "CONNECT":
                updateConnectedClients(connectedClients + 1);
                break;
            case "DISCONNECT":
                updateConnectedClients(Math.max(0, connectedClients - 1));
                break;
        }
    }

    private void togglePlayPause() {
        isPaused.set(!isPaused.get());
        Platform.runLater(() -> {
            playPauseButton.setText(isPaused.get() ? "Tiếp Tục" : "Tạm Dừng");
            updateStatus(isPaused.get() ? "Đã tạm dừng" : "Đang phát");
        });
        sendStatusUpdate(isPaused.get() ? "PAUSED" : "PLAYING");
    }

    private void stopServer() {
        isRunning.set(false);
        sendStatusUpdate("STOPPED");
        Platform.runLater(() -> {
            if (serverStage != null) {
                serverStage.close();
            }
        });
    }

    private void updatePreview(Frame frame, Java2DFrameConverter converter) {
        if (isPreviewUpdating) return;
        isPreviewUpdating = true;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(converter.convert(frame), "jpg", baos);
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

            Platform.runLater(() -> {
                try {
                    Image image = new Image(bais);
                    previewView.setImage(image);
                } finally {
                    isPreviewUpdating = false;
                }
            });
        } catch (Exception e) {
            isPreviewUpdating = false;
            System.err.println("Lỗi cập nhật preview: " + e.getMessage());
        }
    }

    private void sendFrame(Frame frame, int frameNumber, Java2DFrameConverter converter) throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(converter.convert(frame), "jpg", baos);
        byte[] frameData = baos.toByteArray();

        int numPackets = (int) Math.ceil(frameData.length / (double) PACKET_SIZE);
        InetAddress multicastAddr = InetAddress.getByName("224.0.0.1");

        for (int i = 0; i < numPackets; i++) {
            int start = i * PACKET_SIZE;
            int length = Math.min(PACKET_SIZE, frameData.length - start);

            ByteBuffer headerBuffer = ByteBuffer.allocate(12);
            headerBuffer.putInt(frameNumber);
            headerBuffer.putInt(i);
            headerBuffer.putInt(numPackets);

            byte[] packetData = new byte[length + 12];
            System.arraycopy(headerBuffer.array(), 0, packetData, 0, 12);
            System.arraycopy(frameData, start, packetData, 12, length);

            DatagramPacket packet = new DatagramPacket(
                    packetData,
                    packetData.length,
                    multicastAddr,
                    port
            );

            socket.send(packet);
            Thread.sleep(1); // Delay nhỏ để tránh nghẽn mạng
        }
    }

    private void updateStatus(String status) {
        Platform.runLater(() -> statusLabel.setText("Trạng thái: " + status));
    }

    private void updateConnectedClients(int count) {
        connectedClients = count;
        Platform.runLater(() -> connectedClientsLabel.setText("Số người xem: " + count));
    }

    private void sendStatusUpdate(String status) {
        try {
            byte[] statusData = status.getBytes();
            InetAddress multicastAddr = InetAddress.getByName("224.0.0.1");
            DatagramPacket statusPacket = new DatagramPacket(
                    statusData,
                    statusData.length,
                    multicastAddr,
                    port + 2
            );
            socket.send(statusPacket);
        } catch (IOException e) {
            System.err.println("Lỗi gửi cập nhật trạng thái: " + e.getMessage());
        }
    }

    private void cleanup() {
        isRunning.set(false);

        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (FFmpegFrameGrabber.Exception e) {
                System.err.println("Lỗi khi đóng grabber: " + e.getMessage());
            }
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        if (controlSocket != null && !controlSocket.isClosed()) {
            controlSocket.close();
        }

        Platform.runLater(() -> {
            if (serverStage != null) {
                serverStage.close();
            }
        });
    }
}