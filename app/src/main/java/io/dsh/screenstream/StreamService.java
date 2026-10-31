package io.dsh.screenstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private static final long FRAME_INTERVAL_MS = 0L;
    private static final int TARGET_WIDTH = 720;
    private static final int JPEG_QUALITY = 70;
    private static final int THUMB_WIDTH = 360;
    private static final int THUMB_QUALITY = 55;
    /** 宫格：每格宽、列数、保留帧数（凑满才拼一张） */
    private static final int CELL_WIDTH = 240;
    private static final int SHEET_COLS = 2;
    private static final int SHEET_FRAMES = 4;
    private static final int SHEET_QUALITY = 60;
    private static final int DIFF_WIDTH = 60;
    private static final double DIFF_RATIO = 0.03;
    private static final int FULL_QUALITY = 92;

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
    private long incoming;
    private long thumbSeq;
    private long thumbTs;
    private long fullTs;
    private int fullWidth;
    private int fullHeight;
    private int[] lastTiny;
    private final java.util.ArrayDeque<Bitmap> ring = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<long[]> ringMeta = new java.util.ArrayDeque<>();
    private long sheetSeq;
    private long sheetTs;
    private long startAt;
    private long lastSheetAt;
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
        startAt = SystemClock.elapsedRealtime();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels & ~1;
        int height = metrics.heightPixels & ~1;

        captureThread = new HandlerThread("screenstream-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
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

        incoming++;
        long now = SystemClock.elapsedRealtime();
        if (FRAME_INTERVAL_MS > 0 && now - lastAccepted < FRAME_INTERVAL_MS) {
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
            // 按需全分辨率原图：沙盒放 full.req，写一次 full.jpg 后删掉请求
            File fullReq = new File(outDir, "full.req");
            if (fullReq.exists()) {
                writeFull(bitmap);
                //noinspection ResultOfMethodCallIgnored
                fullReq.delete();
            }

            int width = TARGET_WIDTH;
            int height = Math.max(1, Math.round(width * (float) bitmap.getHeight() / bitmap.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            bitmap.recycle();

            File tmp = new File(outDir, "frame.jpg.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            if (!tmp.renameTo(new File(outDir, "frame.jpg"))) {
                Log.w(TAG, "rename failed");
            }
            sequence++;

            if (changed(scaled)) {
                writeThumb(scaled);
            }
            scaled.recycle();
            writeMeta(width, height);
        } catch (Throwable t) {
            Log.e(TAG, "write frame failed", t);
        }
    }

    /** 与上一帧对比：缩到 60 宽取样，差异像素超阈值才算画面变了。 */
    private boolean changed(Bitmap scaled) {
        int w = DIFF_WIDTH;
        int h = Math.max(1, Math.round(w * (float) scaled.getHeight() / scaled.getWidth()));
        Bitmap tiny = Bitmap.createScaledBitmap(scaled, w, h, false);
        int[] pixels = new int[w * h];
        tiny.getPixels(pixels, 0, w, 0, 0, w, h);
        tiny.recycle();
        int[] previous = lastTiny;
        lastTiny = pixels;
        if (previous == null || previous.length != pixels.length) {
            return true;
        }
        int diff = 0;
        for (int i = 0; i < pixels.length; i++) {
            int a = pixels[i];
            int b = previous[i];
            int d = Math.abs((a & 0xff) - (b & 0xff))
                    + Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff))
                    + Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
            if (d > 60) {
                diff++;
            }
        }
        return diff > pixels.length * DIFF_RATIO;
    }

    /** 缩略图进环形队列，凑满 4 帧拼一张带编号的宫格。 */
    private void writeThumb(Bitmap scaled) {
        int w = THUMB_WIDTH;
        int h = Math.max(1, Math.round(w * (float) scaled.getHeight() / scaled.getWidth()));
        Bitmap thumb = Bitmap.createScaledBitmap(scaled, w, h, true);

        int cellW = CELL_WIDTH;
        int cellH = Math.max(1, Math.round(cellW * (float) thumb.getHeight() / thumb.getWidth()));
        Bitmap cell = Bitmap.createScaledBitmap(thumb, cellW, cellH, true);
        ring.addLast(cell);
        ringMeta.addLast(new long[]{sequence, SystemClock.elapsedRealtime() - startAt});
        while (ring.size() > SHEET_FRAMES) {
            Bitmap old = ring.removeFirst();
            if (old != null) old.recycle();
            ringMeta.removeFirst();
        }

        File tmp = new File(outDir, "thumb.jpg.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out);
            if (tmp.renameTo(new File(outDir, "thumb.jpg"))) {
                thumbSeq++;
                thumbTs = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            Log.e(TAG, "write thumb failed", t);
        } finally {
            thumb.recycle();
        }

        // 宫格最多每 800ms 重拼一次，避免画面一直动时狂拼图
        long now = SystemClock.elapsedRealtime();
        if (ring.size() >= SHEET_FRAMES && now - lastSheetAt >= 800) {
            lastSheetAt = now;
            rebuildSheet();
        }
    }

    /** 把队列里的帧画成 2×2 宫格，左上角标序号、下方标相对秒。 */
    private void rebuildSheet() {
        try {
            Bitmap[] cells = ring.toArray(new Bitmap[0]);
            long[][] meta = ringMeta.toArray(new long[0][]);
            int cw = cells[0].getWidth();
            int ch = cells[0].getHeight();
            int rows = (cells.length + SHEET_COLS - 1) / SHEET_COLS;
            int gap = 4;
            int w = SHEET_COLS * cw + (SHEET_COLS + 1) * gap;
            int h = rows * ch + (rows + 1) * gap;
            Bitmap sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(Color.BLACK);

            Paint bg = new Paint();
            bg.setColor(Color.argb(170, 0, 0, 0));
            Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
            fg.setColor(Color.WHITE);
            fg.setTextSize(Math.max(18, ch / 24f));
            fg.setFakeBoldText(true);

            for (int i = 0; i < cells.length; i++) {
                int col = i % SHEET_COLS;
                int row = i / SHEET_COLS;
                int x = gap + col * (cw + gap);
                int y = gap + row * (ch + gap);
                canvas.drawBitmap(cells[i], x, y, null);
                String label = (i + 1) + "";
                canvas.drawRect(x, y, x + fg.measureText(label) + 24, y + fg.getTextSize() + 14, bg);
                canvas.drawText(label, x + 12, y + fg.getTextSize() + 4, fg);
                String sec = String.format(java.util.Locale.US, "%.1fs", meta[i][1] / 1000.0);
                float tw = fg.measureText(sec);
                canvas.drawRect(x + cw - tw - 24, y + ch - fg.getTextSize() - 14,
                        x + cw, y + ch, bg);
                canvas.drawText(sec, x + cw - tw - 12, y + ch - 8, fg);
            }

            File tmp = new File(outDir, "sheet.jpg.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                sheet.compress(Bitmap.CompressFormat.JPEG, SHEET_QUALITY, out);
                if (tmp.renameTo(new File(outDir, "sheet.jpg"))) {
                    sheetSeq++;
                    sheetTs = System.currentTimeMillis();
                    writeSheetMeta(meta);
                }
            }
            sheet.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "sheet failed", t);
        }
    }

    private void writeSheetMeta(long[][] meta) {
        StringBuilder sb = new StringBuilder("{\"frames\":[");
        for (int i = 0; i < meta.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"i\":").append(i + 1)
              .append(",\"seq\":").append(meta[i][0])
              .append(",\"t\":").append(meta[i][1]).append('}');
        }
        sb.append("],\"seq\":").append(sheetSeq)
          .append(",\"ts\":").append(sheetTs)
          .append(",\"cols\":").append(SHEET_COLS).append('}');
        File tmp = new File(outDir, "sheet.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp);
             Writer wr = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            wr.write(sb.toString());
            tmp.renameTo(new File(outDir, "sheet.json"));
        } catch (Throwable t) {
            Log.e(TAG, "sheet meta failed", t);
        }
    }

    private void writeFull(Bitmap bitmap) {
        File tmp = new File(outDir, "full.jpg.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, FULL_QUALITY, out);
            if (tmp.renameTo(new File(outDir, "full.jpg"))) {
                fullTs = System.currentTimeMillis();
                fullWidth = bitmap.getWidth();
                fullHeight = bitmap.getHeight();
                Log.i(TAG, "full frame " + fullWidth + "x" + fullHeight);
            }
        } catch (Throwable t) {
            Log.e(TAG, "write full failed", t);
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
                    + ",\"fps\":" + FPS
                    + ",\"in\":" + incoming
                    + ",\"out\":" + sequence
                    + ",\"thumbSeq\":" + thumbSeq
                    + ",\"thumbTs\":" + thumbTs
                    + ",\"fullTs\":" + fullTs
                    + ",\"fullWidth\":" + fullWidth
                    + ",\"fullHeight\":" + fullHeight
                    + ",\"sheetSeq\":" + sheetSeq
                    + ",\"sheetTs\":" + sheetTs + "}");
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
