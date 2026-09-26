package com.dsh.mediaprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 验证器：用真实的 Android 解码器判断一个 mp4 能不能播，并把报告写到
 * /sdcard/Android/data/com.dsh.mediaprobe/files/report.txt
 *
 * 启动：am start -n com.dsh.mediaprobe/.ProbeActivity --es path <file>
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "MediaProbe";

    private static String atMs = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        atMs = getIntent() == null ? "" : getIntent().getStringExtra("atMs");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    probe(path, sb);
                } catch (Throwable t) {
                    sb.append("!! 探测异常: ").append(t).append('\n');
                }
                Log.i(TAG, sb.toString());
                File out = new File(getExternalFilesDir(null), "report.txt");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(sb.toString().getBytes("UTF-8"));
                } catch (Throwable t) {
                    Log.e(TAG, "write report", t);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { finish(); }
                });
            }
        }, "probe").start();
    }

    static void probe(String path, StringBuilder sb) throws Exception {
        sb.append("file=").append(path).append('\n');
        meta(path, sb);
        decode(path, sb);
    }

    private static void meta(String path, StringBuilder sb) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            sb.append("width=").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
              .append(" height=").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
              .append(" durationMs=").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
              .append(" hasAudio=").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
              .append(" captureFps=").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))
              .append('\n');
            Bitmap bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bmp == null) {
                sb.append("firstFrame=NULL(解码失败)\n");
            } else {
                sb.append("firstFrame=").append(bmp.getWidth()).append('x').append(bmp.getHeight()).append('\n');
                bands("t0", bmp, sb);
                try (java.io.FileOutputStream pf = new java.io.FileOutputStream(
                             "/sdcard/Android/data/com.dsh.mediaprobe/files/frame0.png")) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, pf);
                    sb.append("frame0.png saved\n");
                } catch (Throwable t) { sb.append("save png: ").append(t).append('\n'); }
            }
            // 指定时刻抽帧（毫秒，逗号分隔），用于和真机截图做方向比对
            String at = atMs;
            if (at != null && !at.isEmpty()) {
                for (String part : at.split(",")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    long ms = Long.parseLong(part);
                    Bitmap bb = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (bb == null) {
                        sb.append("frame@" + ms + "=NULL\n");
                    } else {
                        bands("t" + ms, bb, sb);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("!! MediaMetadataRetriever: ").append(t).append('\n');
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** 把帧横向 8 等分，打印每段的平均 RGB：用于和真机截图做方向比对。 */
    private static void bands(String tag, Bitmap b, StringBuilder sb) {
        int w = b.getWidth(), h = b.getHeight();
        StringBuilder line = new StringBuilder();
        line.append("bands").append(tag).append('=');
        for (int k = 0; k < 8; k++) {
            int y0 = h * k / 8, y1 = h * (k + 1) / 8;
            long sr = 0, sg = 0, sb2 = 0, n = 0;
            for (int y = y0; y < y1; y += 3) {
                for (int x = 0; x < w; x += 3) {
                    int p = b.getPixel(x, y);
                    sr += (p >> 16) & 0xFF;
                    sg += (p >> 8) & 0xFF;
                    sb2 += p & 0xFF;
                    n++;
                }
            }
            if (k > 0) line.append(',');
            line.append(n == 0 ? "-1" : (sr / n) + "/" + (sg / n) + "/" + (sb2 / n));
        }
        sb.append(line).append('\n');
    }

    private static void decode(String path, StringBuilder sb) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(path);
            int vIdx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vIdx = i; fmt = f; break; }
            }
            if (vIdx < 0) { sb.append("decode=NO_VIDEO_TRACK\n"); return; }
            sb.append("videoFormat=").append(fmt).append('\n');
            ex.selectTrack(vIdx);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int frames = 0;
            long firstPts = -1, lastPts = 0;
            long deadline = System.currentTimeMillis() + 45000;
            while (!outDone && System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    frames++;
                    if (firstPts < 0) firstPts = info.presentationTimeUs;
                    lastPts = info.presentationTimeUs;
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sb.append("decoderOut=").append(dec.getOutputFormat()).append('\n');
                }
            }
            long span = lastPts - firstPts;
            sb.append("decodedFrames=").append(frames)
              .append(" ptsSpanMs=").append(span / 1000)
              .append(" avgFps=").append(span > 0 ? (frames * 1000000L / span) : 0)
              .append('\n');
            sb.append("verdict=").append(outDone ? "PLAYABLE" : "NOT_DECODABLE").append('\n');
        } catch (Throwable t) {
            sb.append("!! decode: ").append(t).append('\n');
            sb.append("verdict=ERROR\n");
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) { }
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }
}
