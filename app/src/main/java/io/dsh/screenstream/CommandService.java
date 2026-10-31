package io.dsh.screenstream;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍命令执行器。
 * 沙盒侧写 cmd.json，这里轮询执行，结果写 result.json。
 * 与 StreamService 共用 DSHBox/screenstream 目录。
 */
public class CommandService extends AccessibilityService {
    private static final String TAG = "ScreenStreamCmd";
    private static final long POLL_MS = 40L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private File dir;
    private File cmdFile;
    private File resultFile;
    private long lastId = Long.MIN_VALUE;
    private long lastMtime = -1L;
    private final List<JSONObject> queue = new ArrayList<>();
    private boolean busy;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        dir = new File(Environment.getExternalStorageDirectory(), "DSHBox/screenstream");
        if (!dir.exists()) dir.mkdirs();
        cmdFile = new File(dir, "cmd.json");
        resultFile = new File(dir, "result.json");
        handler.post(poll);
        Log.i(TAG, "command service connected");
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (cmdFile.exists() && cmdFile.lastModified() != lastMtime) {
                    lastMtime = cmdFile.lastModified();
                    String text = readAll(cmdFile);
                    JSONObject cmd = new JSONObject(text);
                    long id = cmd.optLong("id", 0L);
                    if (id > lastId) {
                        lastId = id;
                        enqueue(cmd);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "poll failed: " + t);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void enqueue(JSONObject cmd) {
        JSONArray seq = cmd.optJSONArray("do");
        if (seq != null) {
            for (int i = 0; i < seq.length(); i++) {
                JSONObject step = seq.optJSONObject(i);
                if (step != null) queue.add(step);
            }
        } else {
            queue.add(cmd);
        }
        long id = cmd.optLong("id", 0L);
        if (!busy) runNext(id);
    }

    private void runNext(long id) {
        if (queue.isEmpty()) {
            busy = false;
            return;
        }
        busy = true;
        JSONObject step = queue.remove(0);
        execute(step, () -> {
            handler.postDelayed(() -> {
                if (!queue.isEmpty()) {
                    runNext(id);
                } else {
                    busy = false;
                }
            }, 60L);
        });
    }

    private void execute(JSONObject step, Runnable done) {
        String action = step.optString("action", step.optString("a", ""));
        try {
            switch (action) {
                case "tap": {
                    float x = (float) step.optDouble("x");
                    float y = (float) step.optDouble("y");
                    gesture(tapPath(x, y), 60L, done);
                    break;
                }
                case "swipe": {
                    Path p = new Path();
                    p.moveTo((float) step.optDouble("fromX"), (float) step.optDouble("fromY"));
                    p.lineTo((float) step.optDouble("toX"), (float) step.optDouble("toY"));
                    long dur = (long) step.optDouble("durationMs", 250);
                    gesture(p, Math.max(60L, dur), done);
                    break;
                }
                case "text": {
                    String text = step.optString("text", "");
                    boolean ok = setText(text);
                    writeResult(true, ok ? "text set" : "no focused input");
                    done.run();
                    break;
                }
                case "key": {
                    String key = step.optString("key", "back");
                    boolean ok;
                    if ("enter".equals(key)) {
                        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        ok = focus != null && focus.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER);
                    } else if ("home".equals(key)) {
                        ok = performGlobalAction(GLOBAL_ACTION_HOME);
                    } else if ("recents".equals(key)) {
                        ok = performGlobalAction(GLOBAL_ACTION_RECENTS);
                    } else if ("back".equals(key)) {
                        ok = performGlobalAction(GLOBAL_ACTION_BACK);
                    } else {
                        ok = false;
                    }
                    writeResult(true, ok ? "key " + key : "key failed " + key);
                    done.run();
                    break;
                }
                case "open": {
                    String pkg = step.optString("package", "");
                    Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                    boolean ok = false;
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(intent);
                            ok = true;
                        } catch (Throwable t) {
                            Log.w(TAG, "open failed: " + t);
                        }
                    }
                    writeResult(true, ok ? "opened " + pkg : "cannot open " + pkg);
                    done.run();
                    break;
                }
                case "tapText": {
                    String needle = step.optString("text", "");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    AccessibilityNodeInfo hit = findNode(root, needle);
                    boolean ok = false;
                    if (hit != null) {
                        android.graphics.Rect r = new android.graphics.Rect();
                        hit.getBoundsInScreen(r);
                        ok = gesture(tapPath(r.centerX(), r.centerY()), 60L, done);
                        if (!ok) done.run();
                    } else {
                        writeResult(true, "no node: " + needle);
                        done.run();
                    }
                    break;
                }
                case "findText": {
                    String needle = step.optString("text", "");
                    AccessibilityNodeInfo hit = findNode(getRootInActiveWindow(), needle);
                    writeResult(true, hit == null ? "not found" : "found " + hit.getText());
                    done.run();
                    break;
                }
                default:
                    writeResult(false, "unknown action: " + action);
                    done.run();
            }
        } catch (Throwable t) {
            Log.e(TAG, "execute failed", t);
            writeResult(false, "error: " + t);
            done.run();
        }
    }

    private Path tapPath(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return p;
    }

    private boolean gesture(Path path, long durationMs, Runnable done) {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, durationMs);
        boolean dispatched = dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        writeResult(true, "gesture ok");
                        done.run();
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        writeResult(false, "gesture cancelled");
                        done.run();
                    }
                }, handler);
        if (!dispatched) {
            writeResult(false, "dispatch rejected");
            return false;
        }
        return true;
    }

    private boolean setText(String text) {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null && getRootInActiveWindow() != null) {
            focus = getRootInActiveWindow().findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (focus == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && t.toString().contains(needle))
                || (d != null && d.toString().contains(needle))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), needle);
            if (found != null) return found;
        }
        return null;
    }

    private void writeResult(boolean ok, String message) {
        try {
            File tmp = new File(dir, "result.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp);
                 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                JSONObject o = new JSONObject();
                o.put("id", lastId);
                o.put("ok", ok);
                o.put("msg", message);
                o.put("t", System.currentTimeMillis());
                w.write(o.toString());
            }
            if (!tmp.renameTo(resultFile)) {
                Log.w(TAG, "result rename failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "write result failed", t);
        }
    }

    private String readAll(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
