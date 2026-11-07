package com.example.tftbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Pipeline:  MediaProjection -> VirtualDisplay -> ImageReader (RGBA)
 *            -> crop + RGBA->YUV420 -> MediaCodec (H.264) -> BridgeSocket.sendVideo()
 *
 * The encoder emits Annex-B (00 00 00 01 start codes). The first output buffer is SPS/PPS
 * (BUFFER_FLAG_CODEC_CONFIG); we also ask for SPS/PPS to be repeated before every IDR.
 * Whether the console wants Annex-B or AVCC is decided in BridgeSocket.buildVideoPacket().
 */
public class BridgeService extends Service {

    private static final String TAG = "TFT_SVC";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    // ===== Console video format - set from the Bajaj APK (resolution, fps, profile, bitrate) =====
    private static final int OUT_W = 800;
    private static final int OUT_H = 480;
    private static final int FPS = 15;
    private static final int BITRATE = 2_000_000;
    private static final int IFRAME_INTERVAL_S = 1;

    // ===== Crop window inside the phone screen (pixels). TUNE THESE =====
    // Phone is portrait (e.g. 1080x2400); the 800x480 window is cut out of it.
    // Put the crop over the Maps area you want on the dash (maneuver banner / map).
    // Alternative: render Maps in a smaller virtual display or scale instead of cropping.
    private static final int CROP_LEFT = 140;
    private static final int CROP_TOP = 300;

    private static final long FRAME_INTERVAL_MS = 1000 / FPS;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaCodec encoder;

    private HandlerThread imageThread, drainThread;
    private volatile boolean running = false;
    private long lastFrameMs = 0;
    private int cropLeft, cropTop, screenW, screenH;
    private final byte[] rowBuf = new byte[OUT_W * 4];

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;

        // Android 14+: startForeground with the mediaProjection type MUST happen BEFORE getMediaProjection().
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        try {
            BridgeSocket.get().connect(this);
            startCapture(resultCode, data);
            running = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start", e);
            stopSelf();
        }
        // The consent token is single-use on Android 14+, so a system restart can't recreate the projection.
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ capture setup

    private void startCapture(int resultCode, Intent data) throws IOException {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);

        imageThread = new HandlerThread("tft-image"); imageThread.start();
        drainThread = new HandlerThread("tft-drain"); drainThread.start();
        Handler imageHandler = new Handler(imageThread.getLooper());

        // Android 14+: a callback MUST be registered before createVirtualDisplay().
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.i(TAG, "Projection stopped by system/user");
                stopSelf();
            }
        }, imageHandler);

        var bounds = getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        screenW = bounds.width();
        screenH = bounds.height();
        int dpi = getResources().getConfiguration().densityDpi;

        if (screenW < OUT_W || screenH < OUT_H)
            throw new IllegalStateException("Screen smaller than output crop; add scaling");
        cropLeft = Math.max(0, Math.min(CROP_LEFT, screenW - OUT_W));
        cropTop = Math.max(0, Math.min(CROP_TOP, screenH - OUT_H));

        setupEncoder();

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onFrame, imageHandler);

        virtualDisplay = projection.createVirtualDisplay("tft-bridge",
                screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
    }

    // ------------------------------------------------------------------ MediaCodec

    private void setupEncoder() throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S);
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline); // TODO match console decoder
        f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);           // TODO
        f.setInteger("prepend-sps-pps-to-idr-frames", 1);   // repeat SPS/PPS on each keyframe

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        new Handler(drainThread.getLooper()).post(this::drainLoop);
    }

    /** Pulls encoded H.264 off the codec and pushes it to the socket. */
    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running || encoder != null) {
            MediaCodec enc = encoder;
            if (enc == null) break;
            try {
                int idx = enc.dequeueOutputBuffer(info, 10_000);
                if (idx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = enc.getOutputBuffer(idx);
                        byte[] bytes = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(bytes, 0, info.size);

                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        // >>> HAND-OFF TO THE CONSOLE: framing/headers are added inside BridgeSocket <<<
                        BridgeSocket.get().sendVideo(bytes, info.presentationTimeUs, key, config);
                    }
                    enc.releaseOutputBuffer(idx, false);
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "Encoder format: " + enc.getOutputFormat());
                }
            } catch (IllegalStateException e) {
                break; // encoder was stopped
            }
        }
    }

    // ------------------------------------------------------------------ per-frame: crop + convert + queue

    private void onFrame(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null || encoder == null) return;

            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return;   // throttle to FPS
            lastFrameMs = now;

            int inIdx = encoder.dequeueInputBuffer(0);
            if (inIdx < 0) return;                               // encoder busy, drop frame
            Image in = encoder.getInputImage(inIdx);
            if (in == null) { encoder.queueInputBuffer(inIdx, 0, 0, 0, 0); return; }

            rgbaCropToYuv(img, in);
            in.close();
            encoder.queueInputBuffer(inIdx, 0, OUT_W * OUT_H * 3 / 2,
                    SystemClock.elapsedRealtimeNanos() / 1000, 0);
        } catch (Exception e) {
            Log.w(TAG, "Frame error: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    /**
     * Copies the crop window of an RGBA_8888 Image into the encoder's YUV420 input Image
     * (BT.601 limited range). Honors row/pixel strides, so it works whatever layout the
     * codec picked (planar I420 or semi-planar NV12).
     */
    private void rgbaCropToYuv(Image src, Image dst) {
        Image.Plane sp = src.getPlanes()[0];
        ByteBuffer s = sp.getBuffer();
        int sRow = sp.getRowStride();

        Image.Plane[] dp = dst.getPlanes();
        ByteBuffer yb = dp[0].getBuffer(), ub = dp[1].getBuffer(), vb = dp[2].getBuffer();
        int yRow = dp[0].getRowStride();
        int uRow = dp[1].getRowStride(), uPx = dp[1].getPixelStride();
        int vRow = dp[2].getRowStride(), vPx = dp[2].getPixelStride();

        for (int row = 0; row < OUT_H; row++) {
            s.position((cropTop + row) * sRow + cropLeft * 4);
            s.get(rowBuf, 0, OUT_W * 4);
            boolean evenRow = (row & 1) == 0;

            for (int col = 0; col < OUT_W; col++) {
                int r = rowBuf[col * 4] & 0xFF;
                int g = rowBuf[col * 4 + 1] & 0xFF;
                int b = rowBuf[col * 4 + 2] & 0xFF;

                yb.put(row * yRow + col, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));

                if (evenRow && (col & 1) == 0) {   // 4:2:0 -> one chroma sample per 2x2 block
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    ub.put((row >> 1) * uRow + (col >> 1) * uPx, (byte) u);
                    vb.put((row >> 1) * vRow + (col >> 1) * vPx, (byte) v);
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        running = false;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try {
            if (encoder != null) { MediaCodec e = encoder; encoder = null; e.stop(); e.release(); }
        } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        if (imageThread != null) imageThread.quitSafely();
        if (drainThread != null) drainThread.quitSafely();
        BridgeSocket.get().close();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "tft_bridge", "TFT Bridge", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "tft_bridge")
                .setContentTitle("TFT Bridge running")
                .setContentText("Streaming to console")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .build();
    }
}
