package com.dsh.apprecorder;

import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

/** 已安装应用条目。 */
public class AppEntry {

    public String label = "";
    public String pkg = "";
    public int uid = -1;
    public int targetSdk = 0;
    public boolean system;
    public boolean enabled = true;
    public Drawable icon;

    public AppEntry() {
    }

    public AppEntry(String label, String pkg, int uid) {
        this.label = label;
        this.pkg = pkg;
        this.uid = uid;
    }

    /**
     * 安卓 10(Q) 起才有 AudioPlaybackCapture；targetSdk < 29 的应用默认不参与播放捕获，
     * 除非它在清单里显式打开。这里给出一个保守提示。
     */
    public boolean captureLikely() {
        return targetSdk >= 29;
    }

    public static AppEntry from(ApplicationInfo ai, String label, Drawable icon) {
        AppEntry e = new AppEntry();
        e.label = label == null || label.length() == 0 ? ai.packageName : label.toString();
        e.pkg = ai.packageName;
        e.uid = ai.uid;
        e.targetSdk = ai.targetSdkVersion;
        e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        e.enabled = ai.enabled;
        e.icon = icon;
        return e;
    }

    @Override
    public String toString() {
        return label + " (" + pkg + ")";
    }
}
