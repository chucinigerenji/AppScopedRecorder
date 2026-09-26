package com.dsh.apprecorder;

import android.content.Context;
import android.content.SharedPreferences;

/** 参数持久化。 */
public class Prefs {

    private static final String NAME = "apprecorder";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static RecorderConfig loadConfig(Context c) {
        SharedPreferences p = sp(c);
        RecorderConfig cfg = new RecorderConfig();
        cfg.resMode = p.getInt("resMode", cfg.resMode);
        cfg.customLongEdge = p.getInt("customLongEdge", cfg.customLongEdge);
        cfg.fps = p.getInt("fps", cfg.fps);
        cfg.videoBitrate = p.getInt("videoBitrate", cfg.videoBitrate);
        cfg.autoBitrate = p.getBoolean("autoBitrate", cfg.autoBitrate);
        cfg.codec = p.getInt("codec", cfg.codec);
        cfg.audioEnabled = p.getBoolean("audioEnabled", cfg.audioEnabled);
        cfg.audioBitrate = p.getInt("audioBitrate", cfg.audioBitrate);
        cfg.audioSampleRate = p.getInt("audioSampleRate", cfg.audioSampleRate);
        cfg.hideSelf = p.getBoolean("hideSelf", cfg.hideSelf);
        cfg.launchTarget = p.getBoolean("launchTarget", cfg.launchTarget);
        cfg.targetPkg = p.getString("targetPkg", "");
        cfg.targetLabel = p.getString("targetLabel", "");
        cfg.targetUid = p.getInt("targetUid", -1);
        return cfg;
    }

    public static void saveConfig(Context c, RecorderConfig cfg) {
        sp(c).edit()
                .putInt("resMode", cfg.resMode)
                .putInt("customLongEdge", cfg.customLongEdge)
                .putInt("fps", cfg.fps)
                .putInt("videoBitrate", cfg.videoBitrate)
                .putBoolean("autoBitrate", cfg.autoBitrate)
                .putInt("codec", cfg.codec)
                .putBoolean("audioEnabled", cfg.audioEnabled)
                .putInt("audioBitrate", cfg.audioBitrate)
                .putInt("audioSampleRate", cfg.audioSampleRate)
                .putBoolean("hideSelf", cfg.hideSelf)
                .putBoolean("launchTarget", cfg.launchTarget)
                .putString("targetPkg", cfg.targetPkg)
                .putString("targetLabel", cfg.targetLabel)
                .putInt("targetUid", cfg.targetUid)
                .apply();
    }

    public static void addHistory(Context c, String line) {
        String old = sp(c).getString("history", "");
        String[] parts = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < parts.length && i < 9; i++) {
            sb.append('\n').append(parts[i]);
        }
        sp(c).edit().putString("history", sb.toString()).apply();
    }

    public static String history(Context c) {
        return sp(c).getString("history", "");
    }
}
