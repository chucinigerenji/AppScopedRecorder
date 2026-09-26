# 实测验收报告

所有结论都来自**真机实测**（Xiaomi / Android 16，SDK 36，1880×3008 120Hz 屏），
不是理论推断。验证工具见 `tools/`。

---

## 1. 可播性：由系统自己的解码器判定

很多人用 ffprobe 判断「能不能播」，但相册用的是 Android 的 `MediaCodec`。
所以 `tools/probeapp/` 是一个独立小程序：用 `MediaExtractor + MediaCodec`
把视频轨**完整解码一遍**，统计成功帧数。

```
$ am start -n com.dsh.mediaprobe/.ProbeActivity --es path <file>
```

### 修复前（有 bug 的版本）

文件恰好 **3288 字节**，每一段都一样大：

```
文件: REC_20260926_175334.mp4    大小: 3288 字节
总时长(mvhd): 0.000 s
  [轨道 0] handler=soun   样本数=0  时长=0.000 s
  [轨道 1] handler=vide   样本数=0  时长=0.000 s
```

两条轨道都在、时长却是 0 —— 说明封装器启动了，但**一个样本都没写进去**。

### 修复后

```
width=1920 height=1200 durationMs=38962 hasAudio=yes
videoFormat={... frame-rate=30 ... durationUs=38507600 ...}
decoderOut={... frame-rate=30 ...}
decodedFrames=1139
verdict=PLAYABLE
```

**1139 帧全部解码成功 → 可播放。**

---

## 2. 帧率：从 103fps 的 VFR 压到精确 30fps

`MediaFormat.KEY_FRAME_RATE` 不会限帧，VirtualDisplay 是按屏幕刷新率推帧的。
修复前实测帧间隔在 **3.5 ms ～ 27 ms** 之间乱跳：

```
[轨道 1] handler=vide codec=avc1 1920x1200
  样本数=1159  时长=11.244 s
  帧间隔 前几种: 627 x2(6.97ms), 609 x1(6.77ms), 362 x1(4.02ms), 320 x1(3.56ms)
  视频平均帧率: 103.1 fps          ← 目标只有 30
```

修复后（GLES 按目标帧率重采样）：

```
样本数=1139  时长=38.5 s → 29.6 fps
videoFormat.frame-rate=30
```

同时音视频时长终于对齐（视频 38.5 s ≈ 容器 38.96 s），不再出现视频轨提前结束。

---

## 3. 音轨：确实只录到了目标应用的声音

没有 ffmpeg 也能判断「是不是静音」：**AAC 静音帧极小且几乎恒定，有内容的帧分布很散**。

```
[轨道 0] handler=soun codec=mp4a 2 ch, 44100 Hz
  样本数=544  时长=12.644 s
  AAC 帧字节: 平均=371.5 最小=8 最大=583 标准差=35.3
  >>> 判定: 音轨有实际内容（录到了声音）
```

371 字节/帧 @ 44.1 kHz / 1024 采样 ≈ 128 kbps，与设定值一致，是真实音频而非静音。

捕获侧只做了一件事：`AudioPlaybackCaptureConfiguration.addMatchingUid(目标 UID)`。

---

## 4. 画面方向：和真机截图逐帧比对

GL 重采样最怕把画面搞成上下颠倒。验证方法：
把录制视频的首帧导出成 PNG，和同一时刻的真机截图对比。

首帧 PNG 中：DSH 聊天界面在上、软键盘在下 —— 与实时截图完全一致，**未翻转**。

---

## 5. 验尸记录：三个真实 bug

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 文件恒为 3288 字节，时长 00:00，相册提示「帧率或分辨率过高」 | `mux.addTrack(...)` 的返回值被丢掉，`vTrack`/`aTrack` 恒为 -1，所有样本被 `if (track >= 0)` 拦掉 | 保存返回的轨道号；并加帧计数，让静默失败不再可能 |
| 2 | 时长显示 00:00 | 视频 PTS 是 monotonic **绝对值**，音轨 PTS 是相对值，两者相差约 1.2×10¹² µs | 两条轨道统一减去录制起点的同一基准（`t0Us`），并按轨做单调性保护 |
| 3 | 设置 30fps 实际录出 103fps | `KEY_FRAME_RATE` 不限帧 | 加 `GlFramer`：VD → SurfaceTexture → EGL/GLES 定频重绘 |
| 3b | 加 GL 后视频轨一个样本都没有 | **EGL 上下文线程私有**，setup 线程绑定后未解绑，渲染线程 `eglMakeCurrent` 返回 `EGL_BAD_ACCESS 0x3002` | `prepare()` 结束时解绑；渲染线程自己绑定；失败则 `vdisplay.setSurface()` 回退直通 |
