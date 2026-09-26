package com.dsh.apprecorder;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 画质 / 帧率 / 码率 / 音频参数设置面板（全屏 sheet，横竖屏都不会挤掉按钮）。 */
public final class SettingsSheet {

    private SettingsSheet() {
    }

    public static void show(final Activity act, final RecorderConfig cfg, final Runnable onChanged) {
        final Dialog d = new Dialog(act, R.style.AppDialog);

        final FrameLayout wrapper = new FrameLayout(act);
        wrapper.setPadding(Ui.dp(act, 10), Ui.dp(act, 30), Ui.dp(act, 10), Ui.dp(act, 30));

        final LinearLayout outer = Ui.vbox(act);
        outer.setBackground(Ui.bg(Ui.PANEL, 18f, act));

        final ScrollView sv = new ScrollView(act);
        final LinearLayout root = Ui.vbox(act);
        int p = Ui.dp(act, 16);
        root.setPadding(p, Ui.dp(act, 6), p, p);

        // refresh 在控件回调中被引用，但定义在最后 —— 用单元素数组做前向引用
        final Runnable[] R = new Runnable[1];
        final Runnable fire = new Runnable() {
            @Override public void run() {
                if (R[0] != null) R[0].run();
            }
        };

        final TextView estimate = Ui.tv(act, "", 12.5f, Ui.DIM, false);

        // ---------------- 画面 ----------------
        root.addView(section(act, "画面"));
        root.addView(rowLabel(act, "分辨率（长边）"));
        final int[] resVals = {RecorderConfig.RES_ORIGINAL, RecorderConfig.RES_1080P,
                RecorderConfig.RES_720P, RecorderConfig.RES_480P, RecorderConfig.RES_CUSTOM};
        Ui.Segmented resSeg = new Ui.Segmented(act,
                new String[]{"原始", "1080P", "720P", "480P", "自定义"}, resVals,
                Math.max(0, indexOf(resVals, cfg.resMode)), new Ui.Segmented.OnPick() {
            @Override public void onPick(int value, int index) {
                cfg.resMode = value;
                fire.run();
            }
        });
        root.addView(resSeg);

        final TextView customLabel = rowLabel(act, "自定义长边 " + cfg.customLongEdge + " px");
        final SeekBar customBar = new SeekBar(act);
        customBar.setMax(96);
        customBar.setProgress(Math.max(0, Math.min(96, (cfg.customLongEdge - 480) / 20)));
        customBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                cfg.customLongEdge = 480 + progress * 20;
                customLabel.setText("自定义长边 " + cfg.customLongEdge + " px");
                fire.run();
            }
        });
        root.addView(customLabel);
        root.addView(customBar);

        root.addView(rowLabel(act, "帧率"));
        final int[] fpsVals = {15, 24, 30, 60};
        Ui.Segmented fpsSeg = new Ui.Segmented(act,
                new String[]{"15", "24", "30", "60"}, fpsVals,
                Math.max(0, indexOf(fpsVals, cfg.fps)), new Ui.Segmented.OnPick() {
            @Override public void onPick(int value, int index) {
                cfg.fps = value;
                fire.run();
            }
        });
        root.addView(fpsSeg);

        final Switch autoSw = new Switch(act);
        autoSw.setText("自动码率（按分辨率与帧率推荐）");
        autoSw.setTextColor(Ui.TEXT);
        autoSw.setTextSize(13.5f);
        autoSw.setChecked(cfg.autoBitrate);
        root.addView(autoSw);

        final TextView brLabel = rowLabel(act, "");
        final SeekBar brBar = new SeekBar(act);
        brBar.setMax(78); // 1 Mbps .. 40 Mbps，0.5 步进
        brBar.setProgress(Math.max(0, Math.min(78, (cfg.videoBitrate / 500_000) - 2)));
        brBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                cfg.videoBitrate = (int) ((progress + 2) * 500_000L);
                cfg.autoBitrate = false;
                if (autoSw.isChecked()) autoSw.setChecked(false);
                fire.run();
            }
        });
        root.addView(brLabel);
        root.addView(brBar);

        autoSw.setOnCheckedChangeListener((btn, checked) -> {
            cfg.autoBitrate = checked;
            fire.run();
        });

        root.addView(rowLabel(act, "视频编码"));
        final int[] codecVals = {RecorderConfig.CODEC_AVC, RecorderConfig.CODEC_HEVC};
        Ui.Segmented codecSeg = new Ui.Segmented(act,
                new String[]{"H.264 · 兼容好", "H.265 · 更省空间"}, codecVals,
                Math.max(0, indexOf(codecVals, cfg.codec)), new Ui.Segmented.OnPick() {
            @Override public void onPick(int value, int index) {
                cfg.codec = value;
                fire.run();
            }
        });
        root.addView(codecSeg);

        // ---------------- 声音 ----------------
        root.addView(section(act, "声音 · 只录选中应用"));
        final Switch audioSw = new Switch(act);
        audioSw.setText("录制目标应用的声音（其它应用静音）");
        audioSw.setTextColor(Ui.TEXT);
        audioSw.setTextSize(13.5f);
        audioSw.setChecked(cfg.audioEnabled);
        audioSw.setOnCheckedChangeListener((b, c) -> {
            cfg.audioEnabled = c;
            fire.run();
        });
        root.addView(audioSw);

        root.addView(rowLabel(act, "音频码率"));
        final int[] abrVals = {64_000, 96_000, 128_000, 192_000, 256_000};
        Ui.Segmented abrSeg = new Ui.Segmented(act,
                new String[]{"64K", "96K", "128K", "192K", "256K"}, abrVals,
                Math.max(0, indexOf(abrVals, cfg.audioBitrate)), new Ui.Segmented.OnPick() {
            @Override public void onPick(int value, int index) {
                cfg.audioBitrate = value;
                fire.run();
            }
        });
        root.addView(abrSeg);

        root.addView(rowLabel(act, "采样率"));
        final int[] srVals = {44100, 48000};
        Ui.Segmented srSeg = new Ui.Segmented(act,
                new String[]{"44.1 kHz", "48 kHz"}, srVals,
                Math.max(0, indexOf(srVals, cfg.audioSampleRate)), new Ui.Segmented.OnPick() {
            @Override public void onPick(int value, int index) {
                cfg.audioSampleRate = value;
                fire.run();
            }
        });
        root.addView(srSeg);

        // ---------------- 行为 ----------------
        root.addView(section(act, "录制时"));
        final Switch hideSw = new Switch(act);
        hideSw.setText("隐藏本应用界面（避免录进自己）");
        hideSw.setTextColor(Ui.TEXT);
        hideSw.setTextSize(13.5f);
        hideSw.setChecked(cfg.hideSelf);
        hideSw.setOnCheckedChangeListener((b, c) -> {
            cfg.hideSelf = c;
            fire.run();
        });
        root.addView(hideSw);

        final Switch launchSw = new Switch(act);
        launchSw.setText("自动切换到目标应用");
        launchSw.setTextColor(Ui.TEXT);
        launchSw.setTextSize(13.5f);
        launchSw.setChecked(cfg.launchTarget);
        launchSw.setOnCheckedChangeListener((b, c) -> {
            cfg.launchTarget = c;
            fire.run();
        });
        root.addView(launchSw);

        root.addView(Ui.space(act, 0, 14));
        root.addView(estimate);

        sv.addView(root);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------- 底部按钮（固定在面板底部，永不溢出） ----------------
        LinearLayout btns = Ui.hbox(act);
        btns.setPadding(p, Ui.dp(act, 10), p, p);

        TextView reset = Ui.tv(act, "恢复默认", 14f, Ui.DIM, false);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(Ui.dp(act, 14), Ui.dp(act, 13), Ui.dp(act, 14), Ui.dp(act, 13));
        reset.setBackground(Ui.strokeBg(Ui.PANEL2, 12f, Ui.LINE, 1f, act));
        reset.setOnClickListener(v -> {
            RecorderConfig def = new RecorderConfig();
            cfg.resMode = def.resMode;
            cfg.customLongEdge = def.customLongEdge;
            cfg.fps = def.fps;
            cfg.videoBitrate = def.videoBitrate;
            cfg.autoBitrate = def.autoBitrate;
            cfg.codec = def.codec;
            cfg.audioEnabled = def.audioEnabled;
            cfg.audioBitrate = def.audioBitrate;
            cfg.audioSampleRate = def.audioSampleRate;
            cfg.hideSelf = def.hideSelf;
            cfg.launchTarget = def.launchTarget;
            customBar.setProgress(Math.max(0, Math.min(96, (cfg.customLongEdge - 480) / 20)));
            brBar.setProgress(Math.max(0, Math.min(78, (cfg.videoBitrate / 500_000) - 2)));
            autoSw.setChecked(cfg.autoBitrate);
            audioSw.setChecked(cfg.audioEnabled);
            hideSw.setChecked(cfg.hideSelf);
            launchSw.setChecked(cfg.launchTarget);
            resSeg.setIndexByValue(cfg.resMode, false);
            fpsSeg.setIndexByValue(cfg.fps, false);
            codecSeg.setIndexByValue(cfg.codec, false);
            abrSeg.setIndexByValue(cfg.audioBitrate, false);
            srSeg.setIndexByValue(cfg.audioSampleRate, false);
            fire.run();
        });

        TextView done = Ui.tv(act, "完成", 14f, Color.WHITE, true);
        done.setGravity(Gravity.CENTER);
        done.setPadding(Ui.dp(act, 24), Ui.dp(act, 13), Ui.dp(act, 24), Ui.dp(act, 13));
        done.setBackground(Ui.bg(Ui.ACCENT, 12f, act));
        done.setOnClickListener(v -> d.dismiss());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(act, 5), 0, Ui.dp(act, 5), 0);
        reset.setLayoutParams(lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(Ui.dp(act, 5), 0, Ui.dp(act, 5), 0);
        done.setLayoutParams(lp2);
        btns.addView(reset);
        btns.addView(done);
        outer.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrapper.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.setContentView(wrapper);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        R[0] = new Runnable() {
            @Override public void run() {
                boolean custom = cfg.resMode == RecorderConfig.RES_CUSTOM;
                customLabel.setVisibility(custom ? View.VISIBLE : View.GONE);
                customBar.setVisibility(custom ? View.VISIBLE : View.GONE);
                brBar.setEnabled(!cfg.autoBitrate);
                brBar.setAlpha(cfg.autoBitrate ? 0.45f : 1f);

                int[] size = screenSize(act);
                int[] out = RecorderConfig.computeOutputSize(size[0], size[1], cfg);
                int br = cfg.autoBitrate ? cfg.recommendedBitrate(out[0], out[1]) : cfg.videoBitrate;
                brLabel.setText("视频码率 " + RecorderConfig.humanBitrate(br)
                        + (cfg.autoBitrate ? "（自动）" : ""));

                long perMin = br / 8L * 60L + (cfg.audioEnabled ? cfg.audioBitrate / 8L * 60L : 0);
                estimate.setText("输出 " + out[0] + "×" + out[1]
                        + " · " + cfg.fps + "fps"
                        + " · 预计 " + RecorderConfig.humanSize(perMin) + "/分钟"
                        + (cfg.audioEnabled ? "\n含目标应用音频（其它应用的声音不会录进去）"
                        : "\n仅画面，无声"));
                if (onChanged != null) onChanged.run();
            }
        };

        d.setOnDismissListener(dialog -> {
            if (onChanged != null) onChanged.run();
        });
        d.show();
        R[0].run();
    }

    private static int[] screenSize(Context c) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Throwable ignored) { }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static TextView section(Activity a, String title) {
        TextView t = Ui.tv(a, title, 12.5f, Ui.ACCENT, true);
        t.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 6));
        return t;
    }

    private static TextView rowLabel(Activity a, String text) {
        TextView t = Ui.tv(a, text, 13.5f, Ui.TEXT, false);
        t.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        return t;
    }

    /** SeekBar 空实现。 */
    public abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }

        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
