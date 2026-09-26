package com.dsh.probe;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.nio.ByteBuffer;

/**
 * 用真实的 Android 框架解码器验证一个 mp4 到底能不能播：
 *   1) MediaMetadataRetriever 取元数据 + 抽一帧
 *   2) MediaExtractor + MediaCodec 全量解码视频轨，统计成功帧数
 * 另外打印首帧的上/下/左/右亮度，用来判断画面是否上下颠倒。
 */
public class MediaProbe {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: MediaProbe <file.mp4>");
            return;
        }
        String path = args[0];
        System.out.println("=== " + path);

        try {
            meta(path);
        } catch (Throwable t) {
            System.out.println("!! MediaMetadataRetriever 失败: " + t);
        }
        try {
            decode(path);
        } catch (Throwable t) {
            System.out.println("!! 全量解码失败: " + t);
        }
    }

    private static void meta(String path) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        r.setDataSource(path);
        System.out.println("width=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                + " height=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                + " durationMs=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                + " hasAudio=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                + " bitrate=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                + " captureFps=" + r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));

        Bitmap b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (b == null) {
            System.out.println("首帧抽取: NULL（相册大概率也放不出来）");
        } else {
            System.out.println("首帧抽取: " + b.getWidth() + "x" + b.getHeight());
            reportQuadrants(b);
        }
        r.release();
    }

    /** 把首帧按水平四等分，打印每一条带的平均亮度，用于判断是否上下颠倒。 */
    private static void reportQuadrants(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int bands = 4;
        for (int k = 0; k < bands; k++) {
            int y0 = h * k / bands, y1 = h * (k + 1) / bands;
            long sum = 0;
            long n = 0;
            for (int y = y0; y < y1; y += 3) {
                for (int x = 0; x < w; x += 3) {
                    int p = b.getPixel(x, y);
                    sum += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                    n++;
                }
            }
            System.out.println("  横向条带 " + k + " (y " + y0 + "-" + y1 + ") 平均亮度 = "
                    + (n == 0 ? -1 : (sum / n / 3)));
        }
    }

    private static void decode(String path) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int vIdx = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                vIdx = i;
                fmt = f;
                break;
            }
        }
        if (vIdx < 0) {
            System.out.println("解码: 没有视频轨");
            return;
        }
        System.out.println("视频轨格式: " + fmt);
        ex.selectTrack(vIdx);
        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0);
        dec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        int frames = 0;
        long ptsSpanUs = 0;
        long firstPts = -1, lastPts = 0;
        long deadline = System.currentTimeMillis() + 60000;

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
                System.out.println("解码器输出格式: " + dec.getOutputFormat());
            }
        }
        ptsSpanUs = lastPts - firstPts;
        System.out.println("全量解码: 成功 " + frames + " 帧, PTS 跨度 "
                + (ptsSpanUs / 1000) + " ms, 平均 "
                + (ptsSpanUs > 0 ? (frames * 1000000L / ptsSpanUs) : 0) + " fps");
        System.out.println(outDone ? ">>> 结论: Android 解码器可以完整解码，文件可播放"
                : ">>> 结论: 解码未能走完（可能不兼容）");
        try {
            dec.stop();
            dec.release();
        } catch (Throwable ignored) { }
        ex.release();
    }
}
