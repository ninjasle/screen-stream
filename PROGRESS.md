# 屏幕流项目进度（2026-10-31）

## 目标

让 AI 以 15fps 持续“看”手机屏幕。

- 插件路线已交付：`@local/screen-share`（8 个工具，源码 /root/projects/dsh-screen-share）。
- 插件达不到 15fps：`screen.capture` 单帧 0.6–1.5s，`screen.record` 录 1 秒往返 11.5s。
- 改 DSHBox 宿主源码不可行：本机无 JDK / Android SDK，编不出 APK。
- 最终方案：独立小 app `ScreenStream`，自己持 MediaProjection 会话，15fps 写共享目录。

## 通道（关键）

`/storage/emulated/0/DSHBox` ⇄ `/root/share/DSHBox`（bind mount）。
app 写 `/sdcard/DSHBox/screenstream/frame.jpg`，沙盒立刻能读，不需要网络、不需要协议。

## 已完成（2026-10-31）

`/root/projects/screen-stream/` 源码写完：

```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts                AGP 8.6.1, compileSdk 34, minSdk 29, Java 17
app/src/main/AndroidManifest.xml    FOREGROUND_SERVICE_MEDIA_PROJECTION / MANAGE_EXTERNAL_STORAGE
app/src/main/java/io/dsh/screenstream/MainActivity.java   授权 + 开始/停止
app/src/main/java/io/dsh/screenstream/StreamService.java  MediaProjection + ImageReader + VirtualDisplay
.github/workflows/build.yml         CI 构建 debug APK
```

采集参数：真实屏幕分辨率、RGBA_8888、maxImages=2、AUTO_MIRROR、节流 1000/15ms、
缩到 720 宽、JPEG q70、`frame.jpg.tmp` → `frame.jpg` 原子替换、每帧写 `meta.json`
（seq/ts/width/height/fps）、写盘忙时丢帧。

## 待办

1. 推到 GitHub，Actions 出 APK（需要用户 token，或用户自己建库上传）。
2. 装机验证：所有文件访问权限 → 开始共享 → 沙盒读
   `/root/share/DSHBox/screenstream/meta.json` 与 `frame.jpg`。
3. 装机通过后给 `@local/screen-share` 加文件流后端：`screen_watch` 优先读文件流，
   读不到再退回 pilot capture。

## 环境备注

- 无 JDK / Android SDK / aapt2（aarch64），本机编不了 APK。
- ffmpeg：/usr/local/lib/python3.13/dist-packages/imageio_ffmpeg/binaries/ffmpeg-linux-aarch64-v7.0.2
- pip 需加 --break-system-packages。
- Hindsight 记忆服务 401（无 apiToken），暂不可用。
