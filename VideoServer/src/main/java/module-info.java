module VideoStream_LAN_NguyenQuynh {
    requires transitive java.desktop;
    requires javafx.controlsEmpty;
    requires javafx.controls;
    requires javafx.fxmlEmpty;
    requires javafx.fxml;
    requires javafx.baseEmpty;
    requires javafx.base;
    requires javafx.media;
    requires javafx.graphicsEmpty;
    requires javafx.graphics;
    requires org.bytedeco.javacv;
    requires org.bytedeco.javacpp;
    requires org.bytedeco.openblas;
    requires org.bytedeco.opencv;
    requires org.bytedeco.flycapture;
    requires org.bytedeco.libdc1394;
    requires org.bytedeco.libfreenect;
    requires org.bytedeco.libfreenect2;
    requires org.bytedeco.librealsense;
    requires org.bytedeco.librealsense2;
    requires org.bytedeco.videoinput;
    requires org.bytedeco.artoolkitplus;
    requires org.bytedeco.leptonica;
    requires org.bytedeco.tesseract;
    requires org.bytedeco.javacv.platform;
    requires org.bytedeco.openblas.platform;
    requires org.bytedeco.flandmark.platform;
    requires org.bytedeco.flycapture.platform;
    requires org.bytedeco.libdc1394.platform;
    requires org.bytedeco.libfreenect.platform;
    requires org.bytedeco.libfreenect2.platform;
    requires org.bytedeco.librealsense.platform;
    requires org.bytedeco.librealsense2.platform;
    requires org.bytedeco.videoinput.platform;
    requires org.bytedeco.leptonica.platform;
    requires org.bytedeco.tesseract.platform;
    requires org.bytedeco.ffmpeg;
    requires org.bytedeco.opencv.platform;
    requires org.bytedeco.ffmpeg.platform;
    requires org.bytedeco.ffmpeg.windows.x86;
    requires org.bytedeco.ffmpeg.windows.x86_64;
    requires org.bytedeco.javacpp.platform;


    opens com.nguyenquynh to javafx.fxml, javafx.media, javafx.controls;

    exports com.nguyenquynh;
}