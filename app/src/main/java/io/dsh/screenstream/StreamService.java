package io.dsh.screenstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamService extends Service {
    private static final String TAG = "ScreenStream";
    private static final String CHANNEL_ID = "stream";
    private static final int NOTIFICATION_ID = 7001;
    private static final int FPS = 15;
    private static final long FRAME_INTERVAL_MS = 1000L / FPS;
    private static final int TARGET_WIDTH = 720;
    private static final int JPEG_QUALITY = 70;

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private ExecutorService writer;
    private File outDir;
    private long lastAccepted;
    private long sequence;
    private final AtomicBoolean writing = new AtomicBoolean(false);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();

        if (intent == null) {
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.w(TAG, "consent invalid: " + resultCode);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCapture(resultCode, data);
        } catch (Throwable t) {
            Log.e(TAG, "capture start failed", t);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "ScreenStream", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScreenStream 运行中")
                .setContentText("正在把屏幕帧写入 DSHBox/screenstream")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            throw new IllegalStateException("MediaProjection 创建失败");
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "system stopped screen sharing");
                stopSelf();
            }
        }, null);

        outDir = new File(Environment.getExternalStorageDirectory(), "DSHBox/screenstream");
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.w(TAG, "cannot create " + outDir);
        }
        writer = Executors.newSingleThreadExecutor();
        lastAccepted = 0L;
        sequence = 0L;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels & ~1;
        int height = metrics.heightPixels & ~1;

        captureThread = new HandlerThread("screenstream-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        display = projection.createVirtualDisplay(
                "screenstream", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);

        Log.i(TAG, "capture running " + width + "x" + height + " @" + FPS + "fps -> " + outDir);
    }

    private void onImageAvailable(ImageReader source) {
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastAccepted < FRAME_INTERVAL_MS) {
            image.close();
            return;
        }
        lastAccepted = now;

        Bitmap bitmap;
        try {
            bitmap = toBitmap(image);
        } finally {
            image.close();
        }
        if (bitmap == null) {
            return;
        }
        // 写盘跟不上就丢这一帧，只保留最新画面，避免堆积占内存
        if (!writing.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        writer.execute(() -> {
            try {
                writeFrame(bitmap);
            } finally {
                writing.set(false);
            }
        });
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void writeFrame(Bitmap bitmap) {
        try {
            int width = TARGET_WIDTH;
            int height = Math.max(1, Math.round(width * (float) bitmap.getHeight() / bitmap.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            bitmap.recycle();

            File tmp = new File(outDir, "frame.jpg.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            scaled.recycle();

            File target = new File(outDir, "frame.jpg");
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "rename failed");
            }
            sequence++;
            writeMeta(width, height);
        } catch (Throwable t) {
            Log.e(TAG, "write frame failed", t);
        }
    }

    private void writeMeta(int width, int height) {
        File tmp = new File(outDir, "meta.json.tmp");
        File target = new File(outDir, "meta.json");
        try (FileOutputStream out = new FileOutputStream(tmp);
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write("{\"seq\":" + sequence
                    + ",\"ts\":" + System.currentTimeMillis()
                    + ",\"width\":" + width
                    + ",\"height\":" + height
                    + ",\"fps\":" + FPS + "}");
        } catch (Throwable t) {
            Log.e(TAG, "write meta failed", t);
            return;
        }
        if (!tmp.renameTo(target)) {
            Log.w(TAG, "meta rename failed");
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    private void stopCapture() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        if (writer != null) {
            writer.shutdown();
            writer = null;
        }
    }
}
