package io.dsh.screenstream;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("ScreenStream");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 32);
        root.addView(status);

        Button start = new Button(this);
        start.setText("开始共享屏幕");
        start.setOnClickListener(v -> begin());
        root.addView(start, match());

        Button stop = new Button(this);
        stop.setText("停止共享");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, StreamService.class));
            refresh();
        });
        root.addView(stop, match());

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void refresh() {
        boolean storage = Environment.isExternalStorageManager();
        status.setText(storage
                ? "存储权限已就绪，进入 DSHBox/screenstream 写帧"
                : "需要「所有文件访问」权限，点开始会跳到设置");
    }

    private void begin() {
        if (!Environment.isExternalStorageManager()) {
            Intent grant = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            grant.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(grant);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, StreamService.class);
            service.putExtra(StreamService.EXTRA_CODE, resultCode);
            service.putExtra(StreamService.EXTRA_DATA, data);
            startForegroundService(service);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) {
            begin();
        }
    }
}
