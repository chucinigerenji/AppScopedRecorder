package com.dsh.apprecorder;

import android.os.Bundle;

/** 录制参数与目标应用。 */
public class RecorderConfig {

    public static final int RES_ORIGINAL = 0;
    public static final int RES_1080P = 1;
    public static final int RES_720P = 2;
    public static final int RES_480P = 3;
    public static final int RES_CUSTOM = 4;

    public static final int CODEC_AVC = 0;
    public static final int CODEC_HEVC = 1;

    /** 分辨率模式（默认 1080P：兼容性最好） */
    public int resMode = RES_1080P;
    /** 自定义模式下的长边像素 */
    public int customLongEdge = 1600;
    /** 帧率 */
    public int fps = 30;
    /** 视频码率 bps */
    public int videoBitrate = 8_000_000;
    /** 是否根据分辨率/帧率自动决定码率 */
    public boolean autoBitrate = true;
    /** 视频编码器 */
    public int codec = CODEC_AVC;
    /** 是否录制目标应用的声音 */
    public boolean audioEnabled = true;
    /** 音频码率 bps */
    public int audioBitrate = 128_000;
    /** 音频采样率 */
    public int audioSampleRate = 44100;
    /** 录制开始时隐藏本应用界面 */
    public boolean hideSelf = true;
    /** 开始录制后自动切到目标应用 */
    public boolean launchTarget = true;

    public String targetPkg = "";
    public String targetLabel = "";
    public int targetUid = -1;

    public int longEdge() {
        switch (resMode) {
            case RES_1080P: return 1920;
            case RES_720P: return 1280;
            case RES_480P: return 854;
            case RES_CUSTOM: return Math.max(240, customLongEdge);
            default: return 0; // ORIGINAL
        }
    }

    public String resLabel() {
        switch (resMode) {
            case RES_1080P: return "1080P";
            case RES_720P: return "720P";
            case RES_480P: return "480P";
            case RES_CUSTOM: return customLongEdge + "P";
            default: return "原始";
        }
    }

    public String codecLabel() {
        return codec == CODEC_HEVC ? "H.265" : "H.264";
    }

    public String targetMime() {
        return codec == CODEC_HEVC ? "video/hevc" : "video/avc";
    }

    /** 推荐码率：约 0.1 bit / 像素 / 帧 */
    public int recommendedBitrate(int w, int h) {
        return recommendedBitrate(w, h, fps);
    }

    public int recommendedBitrate(int w, int h, int frameRate) {
        double bpp = 0.1;
        long b = (long) (w * (long) h * frameRate * bpp);
        if (b < 1_000_000) b = 1_000_000;
        if (b > 60_000_000) b = 60_000_000;
        return (int) (b / 100_000 * 100_000);
    }

    /** 每分钟预计体积（字节） */
    public long estimatedBytesPerMinute() {
        long total = videoBitrate + (audioEnabled ? audioBitrate : 0);
        return total / 8L * 60L;
    }

    /** 计算最终输出尺寸：等比缩放到目标长边，并对齐到 16 的倍数（编码器最稳）。 */
    public static int[] computeOutputSize(int srcW, int srcH, RecorderConfig cfg) {
        int longEdge = cfg.longEdge();
        int srcLong = Math.max(srcW, srcH);
        double scale = 1.0;
        if (longEdge > 0 && srcLong > longEdge) scale = (double) longEdge / srcLong;
        int w = align16((int) Math.round(srcW * scale));
        int h = align16((int) Math.round(srcH * scale));
        return new int[]{w, h};
    }

    public static int align16(int v) {
        return Math.max(16, ((v + 8) / 16) * 16);
    }

    /** 当前参数下的宏块吞吐量，用于判断播放器兼容性（H.264 Level 5.1 ≈ 983040）。 */
    public long macroblocksPerSecond(int w, int h) {
        return (long) ((w + 15) / 16) * ((h + 15) / 16) * fps;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String humanBitrate(int bps) {
        if (bps >= 1_000_000) {
            double m = bps / 1_000_000.0;
            return String.format(java.util.Locale.US, m == Math.floor(m) ? "%.0f Mbps" : "%.1f Mbps", m);
        }
        return (bps / 1000) + " Kbps";
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putInt("resMode", resMode);
        b.putInt("customLongEdge", customLongEdge);
        b.putInt("fps", fps);
        b.putInt("videoBitrate", videoBitrate);
        b.putBoolean("autoBitrate", autoBitrate);
        b.putInt("codec", codec);
        b.putBoolean("audioEnabled", audioEnabled);
        b.putInt("audioBitrate", audioBitrate);
        b.putInt("audioSampleRate", audioSampleRate);
        b.putBoolean("hideSelf", hideSelf);
        b.putBoolean("launchTarget", launchTarget);
        b.putString("targetPkg", targetPkg);
        b.putString("targetLabel", targetLabel);
        b.putInt("targetUid", targetUid);
        return b;
    }

    public RecorderConfig copy() {
        return fromBundle(toBundle());
    }

    public static RecorderConfig fromBundle(Bundle b) {
        RecorderConfig c = new RecorderConfig();
        if (b == null) return c;
        c.resMode = b.getInt("resMode", c.resMode);
        c.customLongEdge = b.getInt("customLongEdge", c.customLongEdge);
        c.fps = b.getInt("fps", c.fps);
        c.videoBitrate = b.getInt("videoBitrate", c.videoBitrate);
        c.autoBitrate = b.getBoolean("autoBitrate", c.autoBitrate);
        c.codec = b.getInt("codec", c.codec);
        c.audioEnabled = b.getBoolean("audioEnabled", c.audioEnabled);
        c.audioBitrate = b.getInt("audioBitrate", c.audioBitrate);
        c.audioSampleRate = b.getInt("audioSampleRate", c.audioSampleRate);
        c.hideSelf = b.getBoolean("hideSelf", c.hideSelf);
        c.launchTarget = b.getBoolean("launchTarget", c.launchTarget);
        c.targetPkg = b.getString("targetPkg", c.targetPkg);
        c.targetLabel = b.getString("targetLabel", c.targetLabel);
        c.targetUid = b.getInt("targetUid", c.targetUid);
        return c;
    }
}
