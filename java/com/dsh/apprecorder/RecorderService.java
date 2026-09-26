package com.dsh.apprecorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 录制前台服务。
 *
 * 视频：MediaProjection -> VirtualDisplay -> MediaCodec(H.264/H.265, Surface 输入) -> MediaMuxer
 * 音频：MediaProjection -> AudioPlaybackCaptureConfiguration(仅匹配目标应用 uid)
 *       -> AudioRecord -> MediaCodec(AAC) -> 同一个 MediaMuxer
 *
 * 因此录到的声音只有被选中应用的，其它应用和系统提示音都不会进入音轨。
 */
public class RecorderService extends Service {

    private static final String TAG = "AppRecorder";

    public static final String ACTION_START = "com.dsh.apprecorder.action.START";
    public static final String ACTION_STOP = "com.dsh.apprecorder.action.STOP";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_CONFIG = "config";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTI_ID = 0x9A01;

    // ---- 进程内状态（给 UI 查询） ----
    private static volatile boolean sRecording;
    private static volatile boolean sPreparing;
    private static volatile long sStartMs;
    private static volatile String sLastFile = "";
    private static volatile String sLastUri = "";
    private static volatile String sLastError = "";
    private static volatile String sLastNote = "";
    private static volatile int sVideoW;
    private static volatile int sVideoH;
    private static volatile int sVideoFrames;
    private static volatile int sAudioFrames;
    private static volatile int sFps;
    private static volatile int sBitrate;

    public static boolean isRecording() { return sRecording; }

    public static boolean isPreparing() { return sPreparing; }

    public static long startedAt() { return sStartMs; }

    public static String lastFile() { return sLastFile; }

    public static String lastUri() { return sLastUri; }

    public static String lastError() { return sLastError; }

    public static String lastNote() { return sLastNote; }

    public static String lastSize() {
        return sVideoW > 0 ? (sVideoH + "x" + sVideoW) : "-";
    }

    public static String lastQuality() {
        if (sVideoW <= 0) return "-";
        return sFps + "fps · " + RecorderConfig.humanBitrate(sBitrate);
    }

    // ---- 录制资源 ----
    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private MediaCodec vcodec;
    private MediaCodec acodec;
    private AudioRecord audioRecord;
    private Surface vsurface;
    private MediaMuxer rawMuxer;
    private Mux mux;
    private File tempFile;

    private Thread vThread;
    private Thread glThread;
    private Thread aPumpThread;
    private Thread aEncThread;
    private GlFramer framer;

    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean userStopped;
    private volatile long t0Ns;
    private volatile long t0Us;
    private volatile int aSampleRate = 44100;

    private static final byte[] POISON = new byte[0];

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            userStopped = true;
            stopRecording("用户停止");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (sRecording || sPreparing) return START_NOT_STICKY;
            handleStart(intent);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (running) stopRecording("服务销毁");
        super.onDestroy();
    }

    // ==================================================================
    // 启动
    // ==================================================================

    private void handleStart(Intent intent) {
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Bundle cb = intent.getBundleExtra(EXTRA_CONFIG);
        final RecorderConfig cfg = RecorderConfig.fromBundle(cb);

        sPreparing = true;
        sLastError = "";
        sLastNote = "";
        sLastFile = "";
        sLastUri = "";

        // Android 14+ 要求：先以 mediaProjection 类型进入前台，再取 MediaProjection
        try {
            startForeground(NOTI_ID, buildNotification("正在准备录制…", null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Throwable t) {
            sPreparing = false;
            sLastError = "无法进入前台服务：" + t;
            stopSelf();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    setupAndStart(resultCode, resultData, cfg);
                } catch (Throwable t) {
                    Log.e(TAG, "start failed", t);
                    sLastError = describe(t);
                    safeReleaseAll();
                    sPreparing = false;
                    sRecording = false;
                    notifyDone(false);
                    stopSelf();
                }
            }
        }, "rec-setup").start();
    }

    private void setupAndStart(int resultCode, Intent resultData, RecorderConfig cfg) throws Exception {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) throw new IllegalStateException("系统不支持 MediaProjection");
        if (resultData == null || resultCode != Activity.RESULT_OK)
            throw new IllegalStateException("录屏授权被取消");

        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("获取 MediaProjection 失败");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (running && !stopping) stopRecording("系统回收了投屏会话");
            }
        }, main);

        // ---- 屏幕尺寸 ----
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) throw new IllegalStateException("无法获取窗口服务");
        wm.getDefaultDisplay().getRealMetrics(dm);
        int[] wh = computeSize(dm.widthPixels, dm.heightPixels, cfg);
        int width = wh[0], height = wh[1];
        int densityDpi = dm.densityDpi;
        int fps = cfg.fps;
        String compatNote = "";

        // 兼容性保护：帧尺寸/像素吞吐超过 H.264 Level 5.1 时，很多相册与硬件播放器
        // 会直接拒绝（"视频帧率或分辨率过高"）。宁可自动降级也不产出打不开的文件。
        long mbFrame = (long) ((width + 15) / 16) * ((height + 15) / 16);
        if (mbFrame > 36864L) {
            double s = Math.sqrt(36864.0 / mbFrame);
            width = RecorderConfig.align16((int) (width * s));
            height = RecorderConfig.align16((int) (height * s));
            mbFrame = (long) ((width + 15) / 16) * ((height + 15) / 16);
            compatNote = "分辨率已自动下调到 " + width + "x" + height + " 以兼容播放器";
        }
        if (mbFrame * fps > 983040L && fps > 30) {
            fps = 30;
            compatNote = "帧率已自动下调到 30 以兼容播放器";
        }
        if (mbFrame * fps > 983040L && fps > 15) {
            fps = 15;
            compatNote = "帧率已自动下调到 15 以兼容播放器";
        }
        sVideoW = width;
        sVideoH = height;
        sFps = fps;

        int bitrate = cfg.autoBitrate ? cfg.recommendedBitrate(width, height, fps) : cfg.videoBitrate;
        sBitrate = bitrate;

        // ---- 视频编码器 ----
        String mime = cfg.targetMime();
        vcodec = MediaCodec.createEncoderByType(mime);
        MediaFormat vf = MediaFormat.createVideoFormat(mime, width, height);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        vcodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        vsurface = vcodec.createInputSurface();
        // 关键：视频轨 PTS 来自 Surface，是系统 monotonic 的绝对微秒值。
        // 这里记录同一个时钟的起点，两条轨道最后都减去它，时间轴才会一致。
        t0Us = System.nanoTime() / 1000L;
        t0Ns = t0Us * 1000L;
        vcodec.start();

        // ---- 音频（分应用内录）----
        String audioNote = "";
        int uid = cfg.targetUid;
        if (uid < 0) {
            try {
                uid = getPackageManager().getApplicationInfo(cfg.targetPkg, 0).uid;
            } catch (Throwable ignored) { }
        }
        if (cfg.audioEnabled) {
            if (uid < 0) {
                audioNote = "未取得目标应用 UID，本次仅录画面";
            } else {
                try {
                    setupAudio(cfg, uid);
                } catch (Throwable t) {
                    Log.w(TAG, "audio setup failed", t);
                    safeReleaseAudio();
                    audioNote = "内录音频未启用：" + describe(t);
                }
            }
        } else {
            audioNote = "已关闭音频录制";
        }
        sLastNote = compatNote.isEmpty() ? audioNote
                : (audioNote.isEmpty() ? compatNote : compatNote + " · " + audioNote);

        // ---- 封装器 ----
        File dir = getExternalFilesDir("rec");
        if (dir == null) dir = new File(getFilesDir(), "rec");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建输出目录");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        tempFile = new File(dir, "REC_" + stamp + ".mp4");
        rawMuxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int expected = (acodec != null) ? 2 : 1;
        mux = new Mux(rawMuxer, expected, t0Us);

        // ---- 帧率限制器 ----
        // VirtualDisplay 按屏幕刷新率推帧，KEY_FRAME_RATE 并不会限帧。
        // 这里让 VD 渲染到 SurfaceTexture，再用 GLES 以目标帧率重绘进编码器。
        Surface vdTarget = vsurface;
        String fpsNote = "";
        try {
            framer = new GlFramer();
            vdTarget = framer.prepare(vsurface, width, height);
            fpsNote = "GL 精确限帧 " + fps + "fps";
        } catch (Throwable t) {
            Log.w(TAG, "GlFramer 初始化失败，回退直通", t);
            framer = null;
            vdTarget = vsurface;
            fpsNote = "限帧不可用，帧率随屏幕刷新率";
        }
        if (sLastNote == null) sLastNote = "";
        sLastNote = sLastNote.isEmpty() ? fpsNote : sLastNote + " · " + fpsNote;

        // ---- 虚拟屏 ----
        vdisplay = projection.createVirtualDisplay("AppRecorder", width, height, densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                vdTarget, null, null);
        if (vdisplay == null) throw new IllegalStateException("创建虚拟屏失败");

        running = true;
        stopping = false;
        userStopped = false;
        aSampleRate = cfg.audioSampleRate;

        vThread = new Thread(new Runnable() {
            @Override public void run() { videoLoop(); }
        }, "rec-video");
        vThread.start();

        if (framer != null) {
            final int targetFps = fps;
            glThread = new Thread(new Runnable() {
                @Override public void run() { glLoop(targetFps); }
            }, "rec-gl");
            glThread.start();
        }

        if (acodec != null) {
            aPumpThread = new Thread(new Runnable() {
                @Override public void run() { audioPumpLoop(); }
            }, "rec-audio-pump");
            aEncThread = new Thread(new Runnable() {
                @Override public void run() { audioEncodeLoop(); }
            }, "rec-audio-enc");
            aPumpThread.start();
            aEncThread.start();
        }

        sPreparing = false;
        sRecording = true;
        sStartMs = System.currentTimeMillis();
        updateNotification("正在录制 " + cfg.targetLabel, cfg);
    }

    private static int[] computeSize(int srcW, int srcH, RecorderConfig cfg) {
        return RecorderConfig.computeOutputSize(srcW, srcH, cfg);
    }

    /** 对齐到 16 的倍数：绝大多数硬件编码器的最稳妥输入尺寸。 */
    private static int align(int v) {
        return Math.max(16, ((v + 8) / 16) * 16);
    }

    // ==================================================================
    // 音频：AudioPlaybackCapture（只捕获目标 uid）
    // ==================================================================

    private void setupAudio(RecorderConfig cfg, int uid) throws Exception {
        AudioPlaybackCaptureConfiguration apc =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUid(uid)
                        .build();

        int minBuf = AudioRecord.getMinBufferSize(cfg.audioSampleRate,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;
        int bufBytes = Math.max(minBuf * 4, 32768);

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(cfg.audioSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(apc)
                .build();
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord 初始化失败");
        }

        acodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                cfg.audioSampleRate, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, cfg.audioBitrate);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        acodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        acodec.start();
        audioRecord.startRecording();
    }

    private final LinkedBlockingQueue<byte[]> pcmQueue = new LinkedBlockingQueue<>();

    /**
     * 取 PCM 的泵线程。
     * AudioRecord 在内录静音时可能返回 0 字节，此时按墙上时钟补静音，
     * 保证音轨时间轴与画面严格同步，也保证编码器一定会产出格式信息。
     */
    private void audioPumpLoop() {
        final int bytesPerFrame = 4; // 立体声 16bit
        final byte[] buf = new byte[16384];
        long frames = 0;
        while (running) {
            int n;
            try {
                n = audioRecord.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
            } catch (Throwable t) {
                n = -1;
            }
            if (n > 0) {
                byte[] copy = new byte[n];
                System.arraycopy(buf, 0, copy, 0, n);
                offer(copy);
                frames += n / bytesPerFrame;
            } else {
                long expected = (System.nanoTime() - t0Ns) * (long) aSampleRate / 1_000_000_000L;
                long deficit = expected - frames;
                if (deficit > 16) {
                    int fillFrames = (int) Math.min(deficit, buf.length / bytesPerFrame);
                    offer(new byte[fillFrames * bytesPerFrame]);
                    frames += fillFrames;
                } else {
                    sleepQuiet(3);
                }
            }
        }
        offer(POISON);
    }

    private void offer(byte[] c) {
        if (!pcmQueue.offer(c)) {
            // 极端情况：编码器落后，丢弃最旧的一块保持实时
            pcmQueue.poll();
            pcmQueue.offer(c);
        }
    }

    private void audioEncodeLoop() {
        final int bytesPerFrame = 4;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] cur = null;
        int curOff = 0;
        long framesQueued = 0;
        long anchorUs = -1;
        boolean eosSent = false;
        boolean eosRecv = false;

        while (!eosRecv) {
            // ---- 1. 喂数据 ----
            if (!eosSent) {
                if (cur == null) {
                    byte[] c = null;
                    try {
                        c = pcmQueue.poll(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) { }
                    if (c == POISON) {
                        eosSent = trySendEos(anchorUs, framesQueued, bytesPerFrame);
                    } else if (c != null) {
                        cur = c;
                        curOff = 0;
                    }
                }
                if (cur != null && !eosSent) {
                    int inIdx = acodec.dequeueInputBuffer(0);
                    if (inIdx >= 0) {
                        ByteBuffer ib = acodec.getInputBuffer(inIdx);
                        if (ib == null) {
                            acodec.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        } else {
                            int n = Math.min(ib.capacity(), cur.length - curOff);
                            ib.clear();
                            ib.put(cur, curOff, n);
                            if (anchorUs < 0) {
                                // 绝对 monotonic 微秒，与视频轨同一时钟域
                                anchorUs = System.nanoTime() / 1000L;
                            }
                            long pts = anchorUs + framesQueued * 1_000_000L / aSampleRate;
                            acodec.queueInputBuffer(inIdx, 0, n, pts, 0);
                            curOff += n;
                            framesQueued += n / bytesPerFrame;
                            if (curOff >= cur.length) cur = null;
                        }
                    }
                }
            }

            // ---- 2. 取输出 ----
            int outIdx;
            try {
                outIdx = acodec.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                break;
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mux != null) {
                    mux.aTrack = mux.addTrack(acodec.getOutputFormat());
                    Log.i(TAG, "audio track = " + mux.aTrack);
                }
            } else if (outIdx >= 0) {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && mux != null && mux.aTrack >= 0) {
                    ByteBuffer ob = acodec.getOutputBuffer(outIdx);
                    if (ob != null) mux.write(mux.aTrack, ob, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                acodec.releaseOutputBuffer(outIdx, false);
                if (eos) eosRecv = true;
            }
        }
    }

    private boolean trySendEos(long anchorUs, long framesQueued, int bytesPerFrame) {
        int inIdx;
        try {
            inIdx = acodec.dequeueInputBuffer(10000);
        } catch (Throwable t) {
            return true;
        }
        if (inIdx < 0) return false;
        long pts = anchorUs < 0 ? 0 : anchorUs + framesQueued * 1_000_000L / aSampleRate;
        acodec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        return true;
    }

    // ==================================================================
    // 视频
    // ==================================================================

    private void videoLoop() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx;
            try {
                idx = vcodec.dequeueOutputBuffer(info, 10000);
            } catch (IllegalStateException e) {
                break;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mux != null) {
                    mux.vTrack = mux.addTrack(vcodec.getOutputFormat());
                    Log.i(TAG, "video track = " + mux.vTrack);
                }
                continue;
            }
            if (idx < 0) continue;
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    && mux != null && mux.vTrack >= 0) {
                ByteBuffer bb = vcodec.getOutputBuffer(idx);
                if (bb != null) mux.write(mux.vTrack, bb, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                vcodec.releaseOutputBuffer(idx, false);
            } catch (Throwable ignored) { }
            if (eos) break;
        }
    }

    /** 严格按目标帧率把最新画面重绘进编码器；没有新帧时就不产帧。 */
    private void glLoop(int fps) {
        try {
            framer.makeCurrent();
        } catch (Throwable t) {
            Log.e(TAG, "GL 渲染线程绑定 EGL 上下文失败，回退直通渲染", t);
            // 兜底：把虚拟屏直接接回编码器 surface，至少保证有画面（帧率随刷新率）
            try {
                if (vdisplay != null && vsurface != null) vdisplay.setSurface(vsurface);
            } catch (Throwable t2) {
                Log.e(TAG, "回退 setSurface 失败", t2);
            }
            return;
        }
        final long intervalNs = 1_000_000_000L / Math.max(1, fps);
        long next = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            if (now < next) {
                long waitMs = Math.max(1, (next - now) / 1_000_000L);
                framer.awaitFrame(waitMs);
                continue;
            }
            boolean ok;
            try {
                ok = framer.renderOnce();
            } catch (Throwable t) {
                Log.w(TAG, "GL 送帧失败", t);
                break;
            }
            if (!ok) framer.awaitFrame(Math.max(1, intervalNs / 1_000_000L));
            next += intervalNs;
            if (System.nanoTime() - next > intervalNs * 3) next = System.nanoTime();
        }
    }

    // ==================================================================
    // 停止
    // ==================================================================

    private void stopRecording(String reason) {
        synchronized (RecorderService.this) {
            if (stopping) return;
            stopping = true;
        }
        Log.i(TAG, "stop: " + reason);
        running = false;

        try { if (audioRecord != null) audioRecord.stop(); } catch (Throwable ignored) { }
        join(glThread, 2000);
        try { if (vdisplay != null) vdisplay.release(); } catch (Throwable ignored) { }
        try { if (vcodec != null) vcodec.signalEndOfInputStream(); } catch (Throwable ignored) { }

        join(vThread, 4000);
        join(aPumpThread, 2000);
        join(aEncThread, 4000);

        boolean ok = finalizeMuxer();

        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        projection = null;

        safeReleaseAll();

        sRecording = false;
        sPreparing = false;
        if (ok) {
            if (tempFile != null && tempFile.exists() && tempFile.length() > 1024) {
                publishToMediaStore(tempFile);
            }
            String frames = "视频 " + sVideoFrames + " 帧"
                    + (acodec == null && !sRecording ? "" : " / 音频 " + sAudioFrames + " 帧");
            if (sVideoFrames <= 0) {
                sLastError = "没有采集到任何画面帧（视频轨为空），请重试";
            } else if (sLastError.isEmpty()) {
                sLastNote = (sLastNote.isEmpty() ? "" : sLastNote + " · ") + frames;
            }
        }
        notifyDone(ok);
        try { stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    private boolean finalizeMuxer() {
        boolean ok = false;
        try {
            if (mux != null) {
                mux.closing = true;
                sVideoFrames = mux.vCount;
                sAudioFrames = mux.aCount;
                ok = mux.finish();
            }
        } catch (Throwable t) {
            Log.w(TAG, "muxer finish", t);
            sLastError = "封装 MP4 失败：" + describe(t);
        } finally {
            try { if (rawMuxer != null) rawMuxer.release(); } catch (Throwable ignored) { }
            rawMuxer = null;
            mux = null;
        }
        if (!ok) {
            sLastError = sLastError.isEmpty() ? "没有采集到任何画面数据" : sLastError;
        }
        return ok;
    }

    private void notifyDone(boolean ok) {
        String title = ok ? "录制已完成" : "录制失败";
        String text = ok ? (sLastFile.isEmpty() ? "文件已保存" : sLastFile) : sLastError;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            Notification n = buildNotification(title + " · " + text, null);
            nm.notify(NOTI_ID + 1, n);
        }
    }

    private void publishToMediaStore(File src) {
        ContentValues v = new ContentValues();
        String name = src.getName();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/应用定向录屏");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore 插入失败");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("无法打开输出流");
                try (FileInputStream in = new FileInputStream(src)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                os.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            sLastUri = uri.toString();
            sLastFile = "Movies/应用定向录屏/" + name;
            try { src.delete(); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            Log.w(TAG, "publish failed", t);
            // 退而求其次：保留在应用私有目录
            sLastUri = "";
            sLastFile = src.getAbsolutePath();
            if (sLastNote.isEmpty()) sLastNote = "已保存到应用目录（相册写入失败）";
        }
    }

    private void safeReleaseAudio() {
        try { if (audioRecord != null) audioRecord.release(); } catch (Throwable ignored) { }
        audioRecord = null;
        try { if (acodec != null) acodec.stop(); } catch (Throwable ignored) { }
        try { if (acodec != null) acodec.release(); } catch (Throwable ignored) { }
        acodec = null;
    }

    private void safeReleaseAll() {
        try { if (framer != null) framer.release(); } catch (Throwable ignored) { }
        framer = null;
        try { if (audioRecord != null) audioRecord.release(); } catch (Throwable ignored) { }
        audioRecord = null;
        try { if (acodec != null) acodec.stop(); } catch (Throwable ignored) { }
        try { if (acodec != null) acodec.release(); } catch (Throwable ignored) { }
        acodec = null;
        try { if (vcodec != null) vcodec.stop(); } catch (Throwable ignored) { }
        try { if (vcodec != null) vcodec.release(); } catch (Throwable ignored) { }
        vcodec = null;
        try { if (vsurface != null) vsurface.release(); } catch (Throwable ignored) { }
        vsurface = null;
        try { if (vdisplay != null) vdisplay.release(); } catch (Throwable ignored) { }
        vdisplay = null;
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException ignored) { }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }

    private static String describe(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        String cn = t.getClass().getSimpleName();
        if (t instanceof MediaCodec.CodecException) {
            MediaCodec.CodecException ce = (MediaCodec.CodecException) t;
            return cn + "(诊断:" + ce.getDiagnosticInfo() + ") " + (m == null ? "" : m);
        }
        return cn + (m == null ? "" : (": " + m));
    }

    // ==================================================================
    // 封装器（两条轨道都就绪后才 start，先到的一侧先缓存）
    // ==================================================================

    private static final class Mux {
        final MediaMuxer muxer;
        int expected;
        int added;
        boolean started;
        boolean closing;
        int vTrack = -1;
        int aTrack = -1;
        /** 统一时间基准：录制起点（系统 monotonic 微秒），两条轨道都减去它。 */
        final long baseUs;
        long firstSampleMs;
        long vLastUs = -1;
        long aLastUs = -1;
        int vCount;
        int aCount;
        final List<Object[]> pending = new ArrayList<>();

        Mux(MediaMuxer m, int expected, long baseUs) {
            this.muxer = m;
            this.expected = Math.max(1, expected);
            this.baseUs = baseUs;
        }

        synchronized int addTrack(MediaFormat f) {
            if (started || closing) return -1;
            int t;
            try {
                t = muxer.addTrack(f);
            } catch (Throwable e) {
                return -1;
            }
            added++;
            maybeStart();
            return t;
        }

        synchronized void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
            if (closing || track < 0 || info.size <= 0) return;
            byte[] data = new byte[info.size];
            try {
                buf.position(info.offset);
                buf.get(data);
            } catch (Throwable t) {
                Log.w(TAG, "copy sample failed", t);
                return;
            }
            if (firstSampleMs == 0) firstSampleMs = System.currentTimeMillis();
            if (!started) {
                pending.add(new Object[]{track, data, info.presentationTimeUs, info.flags});
                if (pending.size() > 240) maybeStart();
                else if (System.currentTimeMillis() - firstSampleMs > 3000) {
                    // 另一条轨道迟迟没有数据（例如目标应用始终静音），先用现有轨道开箱
                    expected = Math.max(1, added);
                    maybeStart();
                }
            } else {
                writeRaw(track, data, info.presentationTimeUs, info.flags);
            }
        }

        private void maybeStart() {
            if (started || closing) return;
            if (added < expected || added == 0) return;
            try {
                muxer.start();
            } catch (Throwable e) {
                return;
            }
            started = true;
            List<Object[]> copy = new ArrayList<>(pending);
            pending.clear();
            for (Object[] s : copy) {
                writeRaw((Integer) s[0], (byte[]) s[1], (Long) s[2], (Integer) s[3]);
            }
        }

        private void writeRaw(int track, byte[] data, long pts, int flags) {
            if (data.length == 0) return;
            long p = pts - baseUs;
            if (p < 0) p = 0;
            // MediaMuxer 对同一轨道要求严格递增，回退/重复会被丢弃并告警
            if (track == vTrack) {
                if (p <= vLastUs) p = vLastUs + 1;
                vLastUs = p;
            } else {
                if (p <= aLastUs) p = aLastUs + 1;
                aLastUs = p;
            }
            try {
                ByteBuffer b = ByteBuffer.allocateDirect(data.length);
                b.put(data);
                b.position(0);
                b.limit(data.length);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                bi.set(0, data.length, p, flags);
                muxer.writeSampleData(track, b, bi);
                if (track == vTrack) vCount++;
                else aCount++;
            } catch (Throwable t) {
                Log.w(TAG, "writeSampleData", t);
            }
        }

        synchronized boolean finish() {
            closing = true;
            pending.clear();
            if (!started) return false;
            try {
                muxer.stop();
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "muxer.stop", t);
                return false;
            }
        }
    }

    // ==================================================================
    // 通知
    // ==================================================================

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "录屏状态",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("记录录制状态与停止按钮");
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text, RecorderConfig cfg) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (sRecording) {
            b.setUsesChronometer(true).setWhen(sStartMs);
            b.addAction(new Notification.Action.Builder(null, "停止录制", psi).build());
        }
        return b.build();
    }

    private void updateNotification(String text, RecorderConfig cfg) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(text, cfg));
    }
}
