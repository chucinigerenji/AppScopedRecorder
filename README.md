# 应用定向录屏 (AppScopedRecorder)

**只录你选中的那一个应用的声音**的安卓录屏工具。其它应用的声音、系统提示音都不会进音轨，同时画面可调分辨率 / 帧率 / 码率 / 编码器。

纯公开框架 API 实现，**不需要 root，也不需要 Shizuku / ADB**，装上就能用。

```
Android 10 (API 29) 及以上 · 零第三方依赖 · APK 约 53 KB
```

---

## 它解决什么问题

普通录屏软件只有两个选择：录全部系统声音，或者录麦克风。
而 `AudioPlaybackCapture` 允许按 **UID** 精确捕获某一个应用的音频输出——
本应用就是把这个能力做成了一个能用的录屏工具：

```
MediaProjection ──► VirtualDisplay ──► SurfaceTexture ──► GLES 重采样 ──► 视频编码器 ─┐
                                                                                      ├─► MediaMuxer ──► MP4
MediaProjection ──► AudioPlaybackCapture(addMatchingUid) ──► AudioRecord ──► AAC 编码 ─┘
```

关键点：音轨的捕获配置里只加了**目标应用的 UID**，所以进来的只有它的声音。

---

## 功能

| 分类 | 选项 |
|---|---|
| 画面分辨率 | 原始 / 1080P / 720P / 480P / 自定义长边（480–2400 px） |
| 帧率 | 15 / 24 / 30 / 60 |
| 视频码率 | 自动（按分辨率与帧率推荐，约 0.1 bit/像素/帧）或手动 1–40 Mbps |
| 视频编码 | H.264（兼容好）/ H.265（更省空间） |
| 音频 | 开/关；码率 64K–256K；采样率 44.1 / 48 kHz |
| 录制行为 | 录制时隐藏本应用界面、自动切换到目标应用 |
| 输出 | 自动保存到相册 `Movies/应用定向录屏/` |

界面上会实时给出：预估体积（MB/分钟）、实际输出分辨率、以及每个应用是否「可内录」的提示。

---

## 使用

1. 安装 `dist/应用定向录屏-v1.1.apk`
2. 首次启动点右上角「权限」授予录音权限（**不给就只录画面，不会有声音**）
3. 在列表里点选要录的应用（可搜索、可显示系统应用）
4. 点「参数」调分辨率/帧率/码率
5. 点「开始录制」→ 系统弹出录屏授权 → 允许
6. 停止：通知栏的「停止录制」，或回到应用点「停止录制」

---

## 已知限制（都是系统层面的，不是本应用的 bug）

1. **目标应用明确拒绝被捕获时录不到声音。**
   如果它在清单里写了 `android:allowAudioPlaybackCapture="false"`（Netflix、部分视频/音乐类应用），
   任何第三方应用都拿不到它的音频。列表里标「可能静音」的属于这类风险项。

2. **`targetSdk < 29` 的应用默认不参与播放捕获**，需要它自己显式打开。
   列表里对这类应用会显示 `targetSdk<29，系统默认不允许内录它的声音`。

3. **通话类音频（`USAGE_VOICE_COMMUNICATION`）不可捕获**，这是系统硬性规定。

4. **录制开始后请勿旋转屏幕。** 视频尺寸在开始录制时确定，中途旋转会导致画面被拉伸/加边框。

5. 帧率超过 60 或分辨率超过 4K 时，部分相册硬件解码器可能拒绝播放，
   本应用会自动把像素吞吐量压到 H.264 Level 5.1 以内以保证可播。

---

## 构建（无 Gradle）

项目不依赖 Gradle / AndroidX，用 SDK 里的 `aapt2` + `javac` + `d8` + `apksigner` 直接构建：

```bash
# 需要：JDK 17、Android SDK build-tools（aapt2 / apksigner / zipalign）、
#       platform android.jar、r8.jar
# 默认从 $HOME/apkbuild/tools 取 android.jar 与 r8.jar
bash build.sh          # 产出 应用定向录屏-v1.1.apk
```

构建脚本里有一个**必须保留**的细节：

```bash
# 不能用 -bootclasspath android.jar ——
# SDK stub 里的 LambdaMetafactory 缺少 metafactory 方法，会让所有 lambda 编译失败。
# 用 -cp 提供 android.*，java.* 走 JDK，D8 会在 --lib android.jar 下正确脱糖。
javac -source 8 -target 8 -cp "$AJ" ...
```

---

## 源码结构

```
AndroidManifest.xml
build.sh                          无 Gradle 构建脚本
java/com/dsh/apprecorder/
    MainActivity.java             主界面：应用列表 + 参数 + 录制控制
    RecorderService.java          前台服务：投影、视频/音频编码、封装、落盘
    GlFramer.java                 帧率限制器（EGL + GLES 重采样）
    RecorderConfig.java           参数模型 + 输出尺寸计算 + 兼容性保护
    SettingsSheet.java            参数面板
    AppEntry.java / Prefs.java / Ui.java
res/                              图标、主题、颜色
tools/mp4probe.py                 纯 Python 的 MP4 结构自检（盒解析 + AAC 帧统计）
tools/probeapp/                   独立验证器 APK：用系统解码器实测「能不能播」
dist/                             已签名 APK
```

---

## 三个值得一提的坑

开发过程中踩到并修掉的真实 bug，都在 `VERIFICATION.md` 里有实测证据：

1. **`MediaCodec.addTrack` 的返回值不能丢。**
   漏掉轨道号会让 `if (track >= 0)` 永远为假，结果是**一个样本都写不进去**——
   文件恰好 3288 字节、时长 00:00、相册提示「帧率或分辨率过高」。

2. **音视频时间戳必须在同一时钟域。**
   Surface 输入的视频 PTS 是系统 monotonic 的**绝对值**（约 1.2×10¹² µs），
   音轨若按「相对开始时刻」算就会差出天文数字，时长直接报废。
   做法：两条轨道都减去录制起点的同一个 monotonic 微秒基准。

3. **帧率必须真的限。**
   `MediaFormat.KEY_FRAME_RATE` 只是编码器码控的提示，**不会限帧**；
   VirtualDisplay 是按屏幕刷新率推帧的，实测能录出 30–143fps 剧烈抖动的 VFR 流。
   做法：VD → `SurfaceTexture` → EGL/GLES 按目标帧率重绘进编码器。
   注意 **EGL 上下文是线程私有的**：setup 线程绑定后必须先解绑
   （否则渲染线程 `eglMakeCurrent` 会拿到 `EGL_BAD_ACCESS 0x3002`）。

---

## License

MIT
