# ScreenStream

把 Android 屏幕以固定 15fps 持续写到共享目录，供沙盒里的 AI 直接读帧。

不做录屏文件、不做网络服务：只做一件事——`MediaProjection` + `ImageReader` + `VirtualDisplay`，
每攒够 1/15 秒就抓一帧，缩到 720 宽、JPEG 编码，原子替换到：

```
/sdcard/DSHBox/screenstream/frame.jpg
/sdcard/DSHBox/screenstream/meta.json
```

DSHBox 的沙盒里同一路径是 `/root/share/DSHBox/screenstream/`，所以插件不用任何协议、
不用每次申请录屏授权，直接读文件就是最新一帧。

## 为什么这么绕

DSHBox 自带的 `screen.capture` 每次都要新建/拆一个 VirtualDisplay，单帧往返 0.6–1.5 秒；
`screen.record` 录 1 秒往返 11.5 秒。都到不了 15fps。持续流必须由 app 内的
MediaProjection 会话自己维持，而扩展原生能力要重新编译 DSHBox。独立小 app 绕开了这个限制：
它自己拿录屏授权、自己录、只把结果丢进共享目录。

## 用法

1. 安装 `app-debug.apk`
2. 打开，授予「所有文件访问」（写 /sdcard/DSHBox 必需）和通知权限
3. 点「开始共享屏幕」，在系统弹窗里允许录屏
4. 通知栏出现「ScreenStream 运行中」即开始写帧
5. 沙盒里读 `/root/share/DSHBox/screenstream/meta.json` 与 `frame.jpg`

停止：点「停止共享」，或在通知里停服务，或系统回收录屏会话。

## 构建

仓库带 GitHub Actions：push 到 `main` 即在 Actions 里产出 `ScreenStream-debug` 工件。
本机没有 JDK / Android SDK，所以 APK 由 CI 构建。

## 已知限制

- 帧文件是“最新一帧”，没有历史；需要历史由沙盒侧自己攒。
- 15fps 是节流上限，屏幕静止时 MediaProjection 不产帧，实际写入会变慢。
- Android 13 及以下通知权限不申请；Android 14+ 首次启动会要「所有文件访问」。
