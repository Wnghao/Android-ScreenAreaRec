# 区域录制（AreaRec）开发交接

包名 `com.androad.arearec` ｜ minSdk 29 / targetSdk 34 / compileSdk 35 ｜ 零三方依赖

---

## 1. 这个 App 干什么

只录屏幕上**框出来的那一块**，用常驻悬浮条控制开始/暂停/停止/打开文件夹，声音可选（内录 / 麦克风 / 混音），画质三档。输出 MP4 到 `Movies/AreaRec/`。

文件列表页（悬浮条「文件」按钮进入）支持：点击播放、长按出菜单（播放 / **用其他应用打开** / 分享 / 重命名 / 删除）、右上角「选择」进入多选批量删除（带总大小确认、全选、返回键退出）。

---

## 2. 架构

```
MainActivity ──────────┐
                       ├─→ ProjectionRequestActivity（透明中转，弹系统授权框）
OverlayService ────────┘         │
  ├─ 控制条窗口（可拖，自动吸附区域外）  │
  └─ 区域框窗口（编辑可触摸 / 录制态只画外扩边框）
                                 ↓
                          RecordService（mediaProjection 前台服务）
                                 ↓
                            Recorder（协调器）
        ┌────────────┬───────────┴──────────┬─────────────┐
   VirtualDisplay  GlRenderer          VideoEncoder   AudioCapture
   （全屏尺寸）    （UV 裁剪）          （H.264 硬编）  （内录/MIC/混音）
                        └──────────┬──────────┘             │
                              MuxerHolder ←── AudioEncoder ─┘
                                    ↓
                              OutputTarget（MediaStore）
```

### 文件分布

| 文件 | 职责 |
|---|---|
| `core/Areas.kt` | 区域模型、屏幕尺寸、持久化。`AudioMode` 枚举也在这 |
| `core/Quality.kt` | 画质档位（标准 0.09 / 高清 0.18 / 极高 0.30 的码率系数） |
| `core/OutputTarget.kt` | MediaStore 条目创建 / publish / discard |
| `record/RecordingClock.kt` | 共享时间基，暂停时冻结 |
| `record/GlRenderer.kt` | **核心**：EGL + SurfaceTexture + UV 子区域裁剪 |
| `record/VideoEncoder.kt` | H.264 硬编，Surface 输入，独立 drain 线程 |
| `record/AudioEncoder.kt` | AAC，PTS 用采样数推算 |
| `record/AudioCapture.kt` | 三路音频采集 + 混音 |
| `record/MuxerHolder.kt` | MediaMuxer 封装，处理「轨道没凑齐」 |
| `record/Recorder.kt` | 把上面这些串起来，管理线程生命周期 |
| `record/RecordService.kt` | 前台服务，MediaProjection 授权时序 |
| `record/Session.kt` | 进程内状态总线 |
| `overlay/OverlayService.kt` | 悬浮条 + 区域框 + 吸附算法 + 常驻通知面板 |
| `overlay/RegionView.kt` | 区域框的绘制与手势 |
| `RecordTileService.kt` | 控制中心磁贴（一键开始/停止） |
| `RecordingsActivity.kt` | 文件列表：播放 / 用其他应用打开 / 分享 / 重命名 / 单删 / 批量删 |

---

## 3. 三个关键实现

### 3.1 区域裁剪必须过 GL

`VirtualDisplay` 的 width/height 是**缩放**语义，不是裁剪。直接把区域尺寸传进去，
得到的是「整个屏幕缩小到那个尺寸」。

所以：`VirtualDisplay` 永远用**全屏物理像素**，裁剪在 GL 里用 UV 子矩形做：

```
u0 = x / screenW          vTop    = 1 - y / screenH
u1 = (x + w) / screenW    vBottom = 1 - (y + h) / screenH
顶点（TRIANGLE_STRIP）：左下、右下、左上、右上
```

**v 要翻转**：SurfaceTexture 的纹理坐标是 OpenGL 惯例（原点左下），屏幕坐标原点在左上。

最后 `eglPresentationTimeANDROID` 给每帧打 PTS。

### 3.2 暂停 = 冻结时间基

MediaMuxer 没有暂停语义。做法是 `RecordingClock`：暂停时把已流逝时间累进
`accumulatedUs` 并冻结，恢复后从冻结值继续。渲染循环在暂停期间**不绘制**，
编码器收不到帧，暂停那一段在 MP4 里直接被剪掉。

音频不用 clock，用「已送出的采样数 / 采样率」推算 PTS —— 更平滑，且暂停时
`AudioCapture` 停止回调，采样数不增长，天然和视频对齐。

### 3.3 悬浮条自动吸附到区域外

`OverlayService.layoutControl()` 依次试 上 → 下 → 左 → 右 四个位置，
第一个能完整放在屏幕内的就用。四边都放不下（区域接近全屏）才缩到 70% 贴顶部。

这是为了避免悬浮条被录进画面 —— 悬浮窗是系统合成层，GL 拿到的是**已经合成好的画面**，
没法在渲染阶段剔掉，只能物理上挪开。

录制态的边框也是同理：`showBorderWhileRecording` 画的是**外扩 3.5dp** 的矩形，
保证整条线都在区域之外。

### 3.4 帧率按屏幕刷新率自适应

`Screen.nominalRefreshRate()` 读 `display.mode.refreshRate`，`refreshRateBucket()`
归到 30/60/90/120/144 档，然后：

- **可选帧率动态生成**：<60Hz 只给 30；60Hz 给 30/60；120Hz 给 30/60/120
- **默认值**：≥90Hz 的屏幕首次启动自动选 60，否则 30（`Screen.recommendedFps`）
- 用户选过的值如果超出当前屏幕能力（换了显示模式），`Screen.currentFps` 回落到推荐值

**这里有个反直觉的点**：网上关于录屏「帧率必须整除刷新率否则抖动」的说法，
在这套实现里**不成立**。因为渲染是「定时器恒定节拍」驱动的
（`LockSupport.parkNanos` 按固定间隔走），不是 vsync 驱动 ——
每拍调一次 `updateTexImage()` 取当前最新合成帧，屏幕 120Hz 我们取 30，
就是每 4 次合成取 1 次，节奏完全均匀。

只有在「靠 Choreographer 回调、每 N 个 vsync 采一帧」的实现里才会有整除问题。

所以真正要守的只有两条：**别超过屏幕能力**（设 240fps 毫无意义，
VirtualDisplay 最多给 120 帧新内容）、**高刷屏才值得上 60+**。

---

## 4. 踩过的坑（改代码前务必看）

### 4.1 `ProjectionRequestActivity` 绝不能加 `noHistory`

系统授权框是**另一个 Activity**，会把中转页顶成 stopped。`noHistory` 会立刻
finish 掉中转页，`onActivityResult` 永远不回来 —— 表现就是**点「开始」没反应**。

当初就是因为这个，从主页点能成、从悬浮条点经常失败（悬浮条启动时是单 Activity 栈）。

### 4.2 `acceptingAudio` 忘了置 true

`Recorder.start()` 里所有资源就绪后必须 `acceptingAudio = true`，
否则每一块 PCM 都在回调里被丢掉，结果是：视频正常、音轨只有 0.04 秒。

### 4.3 Android 14 的前台服务时序

`RecordService` 里必须 **先 `startForeground()`（带 mediaProjection 类型）
再 `getMediaProjection()`**，顺序反了直接 `SecurityException`。

`OverlayService` 用的 `specialUse` 类型常量是 API 34 才有的，
低版本传进去会被当非法类型，所以按 SDK 版本分支调用。

### 4.4 Surface 输入的 EOS 是 `signalEndOfInputStream()`

不是 `signalEndOfStream()`（那个不存在）。

### 4.5 系统授权框拒绝 adb 注入事件

Android 14+ 的 MediaProjection 授权对话框**不接受 `adb shell input` 的触摸/键盘注入**
（`tap` / `motionevent` / `keyevent` / 甚至 `su` 下都不行）。自动化测试到这一步只能人工点。

### 4.6 悬浮窗 frame 比屏幕矮

`TYPE_APPLICATION_OVERLAY` + `MATCH_PARENT` 在 HyperOS 上拿到的 frame 是
1200×2560（屏幕 1200×2608），会避开导航栏。已加 `FLAG_LAYOUT_NO_LIMITS` 缓解，
但坐标系原点仍是 (0,0)，不影响录制坐标。代价是区域框够不到最底下约 48px。

### 4.7 宽高必须偶数

H.264 要求。区域在 `RegionView` 松手时对齐到 2px，`Recorder` 里再用
`Areas.evenSize()` 兜一层。

### 4.8 VBR 会让录出来的视频「糊」

原本用 `BITRATE_MODE_VBR` + 面积系数 0.09（1114×1312@30 ≈ 3.9Mbps）。
录屏幕 UI 没问题，但**录视频内容时明显发虚** —— H.264 在低码率下先丢高频细节，
观感就是糊，而不是花屏。

改了两处：
- 码率系数提到 0.18（高清档，≈7.9Mbps），并可选 0.30
- **优先用 CBR**（`queryBitrateMode` 先查编码器 capabilities，不支持才退 VBR），
  避免复杂画面时编码器自己往下压

判断是不是这个问题的快捷方法：录一段有细密纹理的画面（比如文字密集的网页），
如果细节糊成一片但整体不花，就是码率不够。

### 4.9 视频类 App 会检测悬浮窗并降质

短视频平台普遍有「检测到录屏/悬浮窗 → 主动降清晰度」的机制，检测手段包括
`TYPE_APPLICATION_OVERLAY` 窗口探测，以及 Android 15 起的
`WindowManager.addScreenRecordingCallback`。

对方做法：`Prefs.KEY_HIDE_OVERLAY` 打开后，录制期间把两个窗口整个从 WindowManager
摘掉（`removeWindowsForRecording`），改用通知栏控制。**前提是通知权限已给**，
否则摘了窗口用户就没法停 —— `shouldHideForRecording()` 里做了这个保护。

如果是 `addScreenRecordingCallback` 那条路，任何窗口操作都绕不过，只能上 hook（LSPosed）。

### 4.10 读刷新率千万别用 `Display.getRefreshRate()`

它返回**实时 vsync 频率**。LTPO 屏（这台 120Hz 的就是）在画面静止时会掉到
10Hz 甚至 1Hz，拿它算推荐值会得到荒谬结果。

用 `display.mode.refreshRate` —— 模式标称值，稳定。

参考这台设备的 `dumpsys display`：

```
1200x2608 120Hz LTPO  modeId=2  renderFrameRate 120.00001
supportedRefreshRates [120, 90, 72, 60, 51.4, 45, 40, 36, 32.7, 30, 27.7]
frameRateCategoryRate {normal=60, high=90}
```

那串 51.4 / 45 / 40 / 36 / 32.7 / 27.7 是 Android 的 frame-rate-override 分频档，
不是可选分辨率。

### 4.11 悬浮条绝对不能贴 y=0

澎湃 OS 的状态栏上方有个 `DynamicIslandWindow`，frame `(0,0)-(1200,156)`，
touchableRegion `(333,19)-(867,143)` —— **比状态栏还高，盖在最高层**。

原来的兜底逻辑在「区域接近全屏、四边都放不下」时会 `y = gap = 24`，
结果控制条正好被灵动岛完全挡住，表现就是**「开了悬浮条但屏幕上什么都没有」**。

现在兜底改成：`y = status_bar_height + 10dp`，并且不缩放（缩放后字太小点不准）。

底部也不行 —— 那里是导航条和 Dock，压上去很难点。

### 4.12 澎湃 OS 会把前台服务的常驻通知折叠进「更多通知」

实测：把渠道 importance 从 MIN 提到 LOW 再到 **DEFAULT（mImportance=3）都没用**，
`flags=ONGOING_EVENT|FOREGROUND_SERVICE` 的通知一律收进「更多通知」。

所以通知里的按钮做了两件事：
1. **用 RemoteViews 自己画按钮**（`res/layout/notif_control.xml`），不用系统的 addAction ——
   action 在这个 ROM 上折叠态根本不渲染
2. **另加一个 QS 磁贴**（`RecordTileService`），控制中心一级可见，这才是一键可达的入口

RemoteViews 的高度压在 60dp 以内，因为 `DecoratedCustomViewStyle` 的折叠态只有 64dp，
超了按钮会被裁掉 —— 而「不展开就能点」正是做这个布局的唯一理由。

注意：通知渠道的 importance 一旦创建就改不了，换级别必须**换 channel id**
（`arearec_overlay` → `_v2` → `_v3`），并在 createChannel 里 deleteNotificationChannel 清旧渠道。

---

## 5. 构建与安装

```bash
cd D:\py\androad\apps\AreaRec
export JAVA_HOME="D:/ProgramFiles/Java/jdk-17.0.2"
export ANDROID_HOME="D:/py/androad/tools/android-sdk"
"D:/py/androad/tools/gradle-8.10.2/bin/gradle.bat" assembleDebug
```

或直接 `bash scripts/build.sh install`。

⚠️ 这台设备（HyperOS）**不能用 `adb install`**，会报 `INSTALL_FAILED_USER_RESTRICTED`。
必须走 root：

```bash
ADB="D:/py/androad/tools/scrcpy/scrcpy-win64-v4.1/adb.exe"
"$ADB" push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/arearec.apk
"$ADB" shell "su -c 'pm install -r -g /data/local/tmp/arearec.apk'"
# 悬浮窗权限 appops 单独给：
"$ADB" shell "su -c 'appops set com.androad.arearec SYSTEM_ALERT_WINDOW allow'"
```

调试日志：

```bash
"$ADB" logcat -d | grep -E "Recorder:|RegionView|OverlayService"
```

---

## 6. 验证状态（2026-10-25）

| 项目 | 状态 | 证据 |
|---|---|---|
| 区域裁剪精确性 | ✅ | 录制 1114×1312，画面里正好显示着「1114 × 1312 @ (25, 635)」，文字无变形 |
| 30fps | ✅ | stsz 403 帧 / 13.43 秒 |
| 麦克风录音 | ✅ | AAC 48kHz mono 127kbps，音轨长度正常 |
| 悬浮条吸附区域外 | ✅ | dumpsys frame 与区域边界比对一致 |
| 区域拖拽 / 8 手柄 | ✅ | 实际拖出过 1114×1312 的区域 |
| 从悬浮条发起授权 | ✅ | ProjectionRequestActivity 正常拉起系统框 |
| **画质档位 / CBR** | ⚠️ 代码完成待测 | 默认高清 ≈7.9Mbps，CBR 按编码器 capabilities 自动选 |
| **帧率自适应** | ✅ | 这台 120Hz 屏自动识别，选项 30/60/120，默认选 60 |
| **通知面板三按钮** | ✅ | RemoteViews，折叠态可见「开始/区域/文件」，在「更多通知」里 |
| **QS 磁贴** | ⚠️ 代码完成 | 需在控制中心编辑里手动添加 |
| **录制时隐藏悬浮条** | ⚠️ 代码完成待测 | 需先给通知权限 |
| **暂停** | ⚠️ 未端到端验证 | 逻辑正确（不渲染=无帧），但系统授权框无法自动化点击，需人工过一遍 |
| **内录** | ⚠️ 未验证 | 首次测试时被 `acceptingAudio` bug 影响，已修但没重测 |
| **混音** | ⚠️ 未验证 | 同上 |
| 文件列表页 | ⚠️ 未验证 | 代码完成，没点过 |

---

## 7. 已知限制（绕不过去）

- **每次录制都要重新授权**，Android 14 起 MediaProjection token 一次性，无法记住
- 授权时选「共享一个应用」会导致只录到那个 App 且尺寸异常，**必须选「整个屏幕」**
- 内录只对 `USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN` 生效，通知音、来电铃声录不到
- `FLAG_SECURE` 页面（银行、支付）和 DRM 内容必然是黑屏
- 录制中旋转屏幕会自动结束并保存（`DisplayListener` 检测到尺寸变化即停）
- HyperOS 需要手动给「自启动」和「后台无限制」，否则可能被杀

---

## 8. 待办 / 可以改进的地方

1. **人工验证暂停**：录 5s → 暂停 5s → 继续 5s，成片应该是 10s 而不是 15s
2. 内录单独测一次（放个音乐/视频再录）
3. **验证画面清晰度**：录视频号/B 站，对比高清档和标准档，确认 7.9Mbps 够用
4. 如果高清档还糊，往上加档位系数（0.25 / 0.35），或者换 HEVC（同码率质量更高，
   但老设备解码兼容性差）
5. 崩溃残留清理：进程被杀会留下 `.pending-*.mp4`，可以在启动时扫一遍删掉
6. 区域预设（16:9 / 1:1 / 竖屏）目前没做
7. 录制中不允许改区域，可以考虑支持动态改（GL 的 UV 是每帧可变的，改起来不难）
8. 分段录制（长时间录制防止单文件过大）
9. **HDR**：dumpsys 显示这台设备 `supportedHdrTypes=[1,2,3,4]`，全支持。
   现在走的是普通 H.264 SDR 管线，录 HDR 内容颜色会偏灰/对比度不对。
   要处理得用 `KEY_COLOR_STANDARD` / `KEY_COLOR_TRANSFER` + HEVC Main10
10. 高帧率 + 高码率时给个实时占用提示（1114×1312@60 极高档是 26Mbps，约 200MB/分钟）
