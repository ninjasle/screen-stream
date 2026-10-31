# 点击 / 滚动可靠性整理（2026-10-31）

目标：让 Agent 在无 root 手机上稳定地点击、滑动、滚动、输入。

## 一、现状

`CommandService`（无障碍）已经能做 tap / swipe / text / key / open / tapText，但存在这些典型问题：

- 点击偶尔"没反应"，但回调返回 ok
- 列表滚不动，或一下滚过头
- 输入框点进去没聚焦，`ACTION_SET_TEXT` 报 no focused input
- WebView / Compose / 游戏这类界面节点树是空的，只能靠坐标

## 二、按可靠性排序的修法

### 1. dispatchGesture 的硬约束

| 约束 | 说明 | 我们的状态 |
| --- | --- | --- |
| 必须在主线程调用 | 非主线程返回 false，且不报错 | ✅ 用 main Handler |
| 必须在服务连接之后 | `onServiceConnected` 之前调用无效 | ✅ |
| 单点 Path 只有 `moveTo` | 部分 ROM 会丢弃零长度的 stroke | ⚠️ 改成 moveTo + lineTo(同点 +1px) |
| `StrokeDescription` 的 startTime 必须为 0 | 否则被排队延迟 | ✅ |
| 连续 gesture 之间留间隔 | 太密会被系统合并/丢弃 | ✅ 60ms，可提到 80ms |
| 服务一旦被系统杀掉就全失效 | 无障碍服务会被省电策略杀 | ⚠️ 需要"自启动 + 无限制" |

### 2. 滚动

`ACTION_SCROLL_FORWARD` 只对声明了 `scrollable` 的节点有效，WebView 里经常无效。可靠做法是**滑动**：

- 距离：至少屏高的 1/3，手机竖屏 400–600px
- 时长：250–400ms。太快被识别成 fling（一下滑很远），太慢被识别成长按
- 起点终点同一 x，避免斜滑触发返回手势
- 边界情况：滑到顶还继续滑，可能触发下拉刷新 —— 需要先看界面再决定方向

建议实现：`scroll` 动作内部先试 `ACTION_SCROLL_FORWARD`，回调 false 就自动改滑动手势。

### 3. 点击的语义层（比"能不能点"更值钱）

参考 [phone-mcp 的通道选型文档](https://raw.githubusercontent.com/PeterTianbuhan/phone-mcp/eea93dae6b7cc428bb6588e9089d699899189fd6/docs/design/PHONE-CONTROL-BACKEND.md)，
他们的结论是：**语义校验比通道本身更重要**。要点：

- **派发 ≠ 成功**。`onCompleted` 只代表系统收下了手势，不代表按钮被按下。
  需要"操作后重新观察界面是否变化"才能判成功。
- **执行前重新定位**。节点坐标会随滚动/动画变化，缓存坐标必失效。
- **去重**。同一个 tap 不要重复发（我们的 `id > lastId` 已有雏形）。
- **单步执行**。一次命令只做一件事，失败了能定位。

### 4. ROM 特有问题

- **小米 / HyperOS**：开发者选项里多一个"USB 调试（安全设置）"，那是给 ADB 用的；
  无障碍本身不需要它，但**需要登录小米账号**、并且给 app 开"自启动"+"省电无限制"，
  否则无障碍服务会被杀。见 [LinkAndroid 的小米说明](https://linkandroid.com/zh/document/25)。
- **Android 13+ 限制**：targetSdk ≥ 31 的应用**不允许**用 Intent 直接跳到无障碍设置页，
  只能提示用户自己进"设置 → 无障碍"。我们现在 `startActivity(ACTION_ACCESSIBILITY_SETTINGS)`
  在部分机型上会被静默拦截。
- **部分 ROM** 有"无障碍服务闲时自动关闭"，需要在设置里关掉。

### 5. 备用通道：Shizuku 的 `input`

Shizuku 已经在这台机器上跑起来了（shell uid 2000），可以用：

```sh
sh /data/local/tmp/... input tap 500 1200
input swipe 540 1800 540 800 300
input text "hello"
input keyevent 4
```

- 优点：走 `INJECT_EVENTS` 权限，**不受任何前台/app 限制**，连 DSH 自己都能操作
- 缺点：每次起一个 shell，200–500ms；但可以一条命令里串多个动作，摊薄成本
- 结论：**当无障碍点击失败时的兜底**，不是主路径

## 三、建议的架构

```
命令 → CommandService（无障碍）
        ├─ 尝试 dispatchGesture / performAction
        ├─ 等完成回调 + 200ms 后看界面是否变化
        └─ 失败（没变化 / 回调 cancelled）
              → 走 Shizuku input 重试一次
              → 还是失败就把界面快照一起返回给模型
```

返回结构建议带：

```json
{"id": 12, "ok": true, "delivered": true, "changed": true, "via": "gesture", "ms": 74}
```

`delivered` 是手势被系统收下，`changed` 是界面真的变了。模型看到 `delivered=true, changed=false`
就知道"点了但没反应"，不会误以为成功。

## 四、下一步动作

1. 单点 tap 改成 moveTo + lineTo（1px），避免零长度 stroke 被丢
2. 加 `scroll` 动作：先 scrollable，再滑动兜底；带方向和距离参数
3. 每个动作完成后 200ms 取一次节点树哈希，判断 `changed`
4. 失败时通过 Shizuku `input` 重试一次
5. 输入框：先点坐标 → 检查 `findFocus(FOCUS_INPUT)` → 没有再点一次 → 最后才 setText
