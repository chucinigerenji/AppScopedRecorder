package com.dsh.apprecorder;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 轻量 UI 工具（不依赖 AndroidX，全部程序化构建）。 */
public final class Ui {

    public static final int BG = 0xFF0B0E13;
    public static final int PANEL = 0xFF151A22;
    public static final int PANEL2 = 0xFF1E2530;
    public static final int LINE = 0xFF262E3A;
    public static final int ACCENT = 0xFF3D7BFF;
    public static final int REC = 0xFFFF4B4B;
    public static final int TEXT = 0xFFE8EDF5;
    public static final int DIM = 0xFF8A94A6;
    public static final int OK = 0xFF35C759;
    public static final int WARN = 0xFFFFB020;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable bg(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable strokeBg(int fill, float radiusDp, int strokeColorContent,
                                            float strokeDp, Context c) {
        GradientDrawable d = bg(fill, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColorContent);
        return d;
    }

    public static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View space(Context c, int w, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), dp(c, h)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        v.setLayoutParams(lp);
        v.setBackgroundColor(LINE);
        return v;
    }

    /** 分段选择器（单选 chip 行）。 */
    public static class Segmented extends LinearLayout {

        public interface OnPick {
            void onPick(int value, int index);
        }

        private final TextView[] views;
        private final int[] values;
        private int selected;
        private OnPick listener;

        public Segmented(Context c, String[] labels, int[] vals, int initial, OnPick l) {
            super(c);
            setOrientation(HORIZONTAL);
            this.values = vals;
            this.listener = l;
            this.selected = Math.max(0, Math.min(initial, labels.length - 1));
            views = new TextView[labels.length];
            int pad = dp(c, 9);
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView t = tv(c, labels[i], 13f, DIM, false);
                t.setGravity(Gravity.CENTER);
                t.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(c, 2), 0, dp(c, 2), 0);
                t.setLayoutParams(lp);
                t.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { setIndex(idx, true); }
                });
                views[i] = t;
                addView(t);
            }
            paint();
        }

        public void setListener(OnPick l) {
            this.listener = l;
        }

        public int value() {
            return values[selected];
        }

        public int index() {
            return selected;
        }

        public void setIndex(int idx, boolean fire) {
            if (idx < 0 || idx >= views.length) return;
            selected = idx;
            paint();
            if (fire && listener != null) listener.onPick(values[idx], idx);
        }

        public void setIndexByValue(int value, boolean fire) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == value) { setIndex(i, fire); return; }
            }
        }

        private void paint() {
            Context c = getContext();
            for (int i = 0; i < views.length; i++) {
                boolean on = i == selected;
                views[i].setBackground(on
                        ? bg(ACCENT, 9f, c)
                        : strokeBg(PANEL2, 9f, LINE, 0.8f, c));
                views[i].setTextColor(on ? Color.WHITE : DIM);
                views[i].setTypeface(views[i].getTypeface(),
                        on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }
}
