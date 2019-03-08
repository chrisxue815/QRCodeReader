package com.locatible.qrcodereader;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.scandit.barcodepicker.ScanSettings;
import com.scandit.recognition.Barcode;
import com.scandit.recognition.BarcodeScanner;
import com.scandit.recognition.BarcodeScannerSession;
import com.scandit.recognition.ImageDescription;
import com.scandit.recognition.RecognitionContext;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;

import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_ARGB;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_BGR24;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_GRAY8;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_RGB24;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_RGBA;


public class MainActivity extends AppCompatActivity {

    private static final String VideoUrl = "rtsp://192.168.0.80";
    private static final String ScanditSdkAppKey = "AVIMQwRFGcoxJgGUhzv6j6Yo1jX3NNeL5kbO6AFUAIxDfr/GjndhnkgfnNLxeemxOWtKwgZ3hTFTAy2TP2yBH/0JHZ8FaMKLnwW/4DheP4riS5XsQxBHAJkwqfBgCzB2GUJiJCulop8Av1dV4wef9ZG+dHiBUUA9jyVlnw2aBYD/wXK2PkOsu76jkzHeiNODQgsvUvAoXKRz9PpspZ6jzbgzGn6azOSToDTT4gD9sqleZGIO69/m4vls8i4j66svDM7m3gZjG2x03SwPBUICQw7q38wu5ZSgHi0XbPQfUdpadH+AsAY/EZI9F1DcUvm4Y+dV6PyiZoVwI93VtOJtBAe56xFhoBFWleeo0nPJB38NqNNUnX7BgI/Zc5p2od/lzJKp0OltFgiHH+5FzhDsMF5Tx6TjCqrGzxOETGumCHwPGLXautnXTWEtXY5k0zds6ZH4I/sJ/WOuX1SSdvj7tOQKDpVAYNm3AB0rPo0XGfvfxbpmJoQCO+gpzfulwvyaTNBiCQNHwhkJbEQS2SXhmsGlDkq4CArg4/VvoUyxt4jzw/ewwuxNudNhQ9veRDp37mCnl35DGKcJvWB20qHO4EDTw1YLgRoH/tSgrmGin7JgS8SHTNYYigc71I2lloXCN1XZvbjU92DSfpl56fiesZ12DaMuKkrv2pxiGDE6y3yNHywrj7jpkSflUDq3Y97kr7fLpOOOe5epiqmv00w2kT1bgRVkaxySjkuKptcjwd8C9agegxT7ShKiTjxG/7YEiSCi7ecXCshzVg/B65O8KnmFkVSSAzElAtAf//9UXyHtHA==";

    private Thread videoProcessorThread;
    private Runnable videoProcessor = new Runnable() {
        @Override
        public void run() {
            try {
                processVideo();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();
    }

    private void init() {
        videoProcessorThread = new Thread(videoProcessor, "processVideo");
        videoProcessorThread.start();
    }

    private void processVideo() throws IOException {
        BarcodeScanner scanner = createScanner();
        RecognitionContext context = scanner.getContext();
        BarcodeScannerSession session = scanner.getSession();

        try (FFmpegFrameGrabber video = openVideo(VideoUrl)) {
            video.setOption("rtsp_transport", "tcp");
            video.start();
            System.out.println("Streaming...");

            ImageDescription imageDescription = createImageDescription(video);
            byte[] frameArray = new byte[0];

            for (; ; ) {
                Frame frame = video.grabImage();

                if (frame == null) {
                    System.out.println("Done");
                    break;
                }

                if (frame.image == null || frame.image.length == 0) {
                    continue;
                }

                ByteBuffer frameBuffer = (ByteBuffer) frame.image[0];
                frameBuffer.position(0);
                int size = frameBuffer.remaining();

                if (size == 0) {
                    continue;
                }

                if (size > frameArray.length) {
                    frameArray = new byte[size * 2];
                }

                frameBuffer.get(frameArray, 0, size);
                imageDescription.setMemorySize(size);

                context.processFrame(imageDescription, frameArray);

                for (Barcode newlyRecognizedCode : session.getNewlyRecognizedCodes()) {
                    System.out.println(newlyRecognizedCode.getData());
                }
            }
        }
    }

    private BarcodeScanner createScanner() {
        ScanSettings settings = ScanSettings.create();
        settings.setSymbologyEnabled(Barcode.SYMBOLOGY_QR, true);

        RecognitionContext context = RecognitionContext.create(this, ScanditSdkAppKey, getNoBackupFilesDir());
        context.startNewFrameSequence();

        return BarcodeScanner.create(context, settings.getBarcodeScannerSettings());
    }

    private FFmpegFrameGrabber openVideo(String url) throws IOException {
        if (url.startsWith("rtsp:")) {
            return new FFmpegFrameGrabber(url);

        } else if (url.startsWith("http")) {
            InputStream inputStream = new URL(url).openConnection().getInputStream();
            return new FFmpegFrameGrabber(inputStream);
        }

        return new FFmpegFrameGrabber(url);
    }

    private ImageDescription createImageDescription(FFmpegFrameGrabber video) {
        ImageDescription imageDescription = new ImageDescription();
        imageDescription.setWidth(video.getImageWidth());
        imageDescription.setHeight(video.getImageHeight());
        imageDescription.setLayout(getLayout(video.getPixelFormat()));
        return imageDescription;
    }

    private int getLayout(int pixelFormat) {
        switch (pixelFormat) {
            case AV_PIX_FMT_GRAY8:
                return ImageDescription.IMAGE_LAYOUT_GRAY_8U;
            case AV_PIX_FMT_RGB24:
            case AV_PIX_FMT_BGR24:
                return ImageDescription.IMAGE_LAYOUT_RGB_8U;
            case AV_PIX_FMT_RGBA:
                return ImageDescription.IMAGE_LAYOUT_RGBA_8U;
            case AV_PIX_FMT_ARGB:
                return ImageDescription.IMAGE_LAYOUT_ARGB_8U;
            default:
                return ImageDescription.IMAGE_LAYOUT_UNKNOWN;
        }
    }
}
