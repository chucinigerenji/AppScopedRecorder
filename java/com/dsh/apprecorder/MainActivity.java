package com.dsh.apprecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

/**
 * 主界面：选目标应用 → 调画质参数 → 开始/停止录制。
 * 不依赖 Shizuku，也不需要 root：全部使用公开框架 API。
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 0x1001;
    private static final int REQ_PERMS = 0x1002;

    private RecorderConfig cfg;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private AppAdapter adapter;

    private EditText search;
    private TextView sysChip;
    private TextView permChip;
    private TextView targetCard;
    private TextView paramBtn;
    private TextView startBtn;
    private TextView emptyHint;
    private LinearLayout recordPanel;
    private TextView recTime;
    private TextView recInfo;
    private TextView recTarget;

    private boolean showSystem;
    private boolean askedPerms;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshState();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Prefs.loadConfig(this);
        buildUi();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        refreshState();
        refreshPermChip();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    public void onBackPressed() {
        if (RecorderService.isRecording()) {
            toast("正在录制中，可从通知栏停止");
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    // ==================================================================
    // UI
    // ==================================================================

    private void buildUi() {
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.hbox(this);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 6));
        header.addView(Ui.tv(this, "应用定向录屏", 20f, Ui.TEXT, true));
        header.addView(spacer());
        permChip = Ui.tv(this, "权限", 11.5f, Ui.DIM, true);
        permChip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        permChip.setOnClickListener(v -> onPermChipClick());
        header.addView(permChip);
        root.addView(header);

        TextView sub = Ui.tv(this, "只录制选中应用的画面与声音；其它应用、系统提示音都不会进音轨",
                12f, Ui.DIM, false);
        sub.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(sub);

        LinearLayout searchRow = Ui.hbox(this);
        searchRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setTextSize(14f);
        search.setSingleLine(true);
        search.setBackground(Ui.strokeBg(Ui.PANEL, 12f, Ui.LINE, 1f, this));
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        search.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            @Override public void afterTextChanged(Editable s) { }
        });
        searchRow.addView(search);

        sysChip = Ui.tv(this, "系统应用", 12f, Ui.DIM, false);
        sysChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        sysChip.setBackground(Ui.strokeBg(Ui.PANEL, 12f, Ui.LINE, 1f, this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        sysChip.setLayoutParams(clp);
        sysChip.setOnClickListener(v -> {
            showSystem = !showSystem;
            paintSysChip();
            loadApps();
        });
        searchRow.addView(sysChip);
        root.addView(searchRow);
        paintSysChip();

        targetCard = Ui.tv(this, "", 13f, Ui.TEXT, false);
        targetCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        targetCard.setBackground(Ui.strokeBg(0xFF131C2B, 14f, Ui.ACCENT, 1f, this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 6));
        targetCard.setLayoutParams(tlp);
        root.addView(targetCard);

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(Color.TRANSPARENT);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = shown.get(position);
            cfg.targetPkg = e.pkg;
            cfg.targetLabel = e.label;
            cfg.targetUid = e.uid;
            Prefs.saveConfig(this, cfg);
            adapter.notifyDataSetChanged();
            refreshState();
        });
        content.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyHint = Ui.tv(this, "正在读取应用列表…", 13f, Ui.DIM, false);
        emptyHint.setGravity(Gravity.CENTER);
        content.addView(emptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        recordPanel = buildRecordPanel();
        recordPanel.setVisibility(View.GONE);
        content.addView(recordPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content);

        LinearLayout bottom = Ui.hbox(this);
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 16));

        paramBtn = Ui.tv(this, "", 12.5f, Ui.TEXT, false);
        paramBtn.setGravity(Gravity.CENTER);
        paramBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
        paramBtn.setBackground(Ui.strokeBg(Ui.PANEL, 14f, Ui.LINE, 1f, this));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp.setMargins(0, 0, Ui.dp(this, 8), 0);
        paramBtn.setLayoutParams(plp);
        paramBtn.setOnClickListener(v -> SettingsSheet.show(this, cfg, () -> {
            Prefs.saveConfig(this, cfg);
            refreshState();
        }));
        bottom.addView(paramBtn);

        startBtn = Ui.tv(this, "开始录制", 15f, Color.WHITE, true);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 14));
        startBtn.setOnClickListener(v -> {
            if (RecorderService.isRecording()) stopRecording();
            else beginRecord();
        });
        bottom.addView(startBtn);
        root.addView(bottom);

        setContentView(root);
        refreshState();
        refreshPermChip();
    }

    private LinearLayout buildRecordPanel() {
        LinearLayout p = Ui.vbox(this);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(Ui.BG);

        TextView dot = Ui.tv(this, "● 正在录制", 16f, Ui.REC, true);
        dot.setGravity(Gravity.CENTER);
        p.addView(dot);

        recTime = Ui.tv(this, "00:00:00", 46f, Ui.TEXT, true);
        recTime.setGravity(Gravity.CENTER);
        recTime.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        p.addView(recTime);

        recTarget = Ui.tv(this, "", 14f, Ui.TEXT, false);
        recTarget.setGravity(Gravity.CENTER);
        p.addView(recTarget);

        recInfo = Ui.tv(this, "", 12.5f, Ui.DIM, false);
        recInfo.setGravity(Gravity.CENTER);
        recInfo.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        p.addView(recInfo);

        TextView tip = Ui.tv(this, "录制中请勿旋转屏幕；可从通知栏或这里停止", 12f, Ui.DIM, false);
        tip.setGravity(Gravity.CENTER);
        p.addView(tip);

        TextView stop = Ui.tv(this, "停止录制", 15f, Color.WHITE, true);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(Ui.dp(this, 40), Ui.dp(this, 14), Ui.dp(this, 40), Ui.dp(this, 14));
        stop.setBackground(Ui.bg(Ui.REC, 14f, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 22), 0, 0);
        stop.setLayoutParams(lp);
        stop.setOnClickListener(v -> stopRecording());
        p.addView(stop);
        return p;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return v;
    }

    private void paintSysChip() {
        sysChip.setTextColor(showSystem ? Color.WHITE : Ui.DIM);
        sysChip.setBackground(showSystem
                ? Ui.bg(Ui.ACCENT, 12f, this)
                : Ui.strokeBg(Ui.PANEL, 12f, Ui.LINE, 1f, this));
    }

    // ==================================================================
    // 权限
    // ==================================================================

    private List<String> missingPerms() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        return need;
    }

    private void refreshPermChip() {
        List<String> need = missingPerms();
        boolean micOk = !need.contains(Manifest.permission.RECORD_AUDIO);
        if (micOk) {
            permChip.setText(need.isEmpty() ? "权限就绪" : "通知未开");
            permChip.setTextColor(Color.WHITE);
            permChip.setBackground(Ui.bg(0xFF123524, 20f, this));
        } else {
            permChip.setText("缺录音权限");
            permChip.setTextColor(Ui.WARN);
            permChip.setBackground(Ui.bg(0xFF2A2113, 20f, this));
        }
    }

    private void onPermChipClick() {
        List<String> need = missingPerms();
        if (need.isEmpty()) {
            toast("录音与通知权限都已就绪");
            return;
        }
        if (askedPerms) {
            openAppSettings();
            return;
        }
        askedPerms = true;
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("请到系统设置里手动授予录音权限");
        }
    }

    // ==================================================================
    // 状态刷新
    // ==================================================================

    private void refreshState() {
        boolean rec = RecorderService.isRecording();
        boolean prep = RecorderService.isPreparing();

        startBtn.setText(rec ? "停止录制" : "开始录制");
        startBtn.setBackground(Ui.bg(rec ? Ui.REC : Ui.ACCENT, 14f, this));
        startBtn.setEnabled(!prep);

        paramBtn.setText("参数 · " + cfg.resLabel() + " / " + cfg.fps + "fps / "
                + (cfg.autoBitrate ? "自动码率" : RecorderConfig.humanBitrate(cfg.videoBitrate))
                + " · " + cfg.codecLabel()
                + (cfg.audioEnabled ? " · 内录目标应用" : " · 无声"));

        if (cfg.targetPkg == null || cfg.targetPkg.isEmpty()) {
            targetCard.setText("尚未选择目标应用 —— 请在下方列表点选一个");
            targetCard.setTextColor(Ui.DIM);
        } else {
            String cap = "";
            for (AppEntry e : allApps) {
                if (e.pkg.equals(cfg.targetPkg)) {
                    cap = e.captureLikely() ? "" : "\n⚠ targetSdk<29，系统默认不允许内录它的声音";
                    break;
                }
            }
            targetCard.setText("目标应用：" + cfg.targetLabel + "\n" + cfg.targetPkg
                    + "  ·  uid " + cfg.targetUid + cap);
            targetCard.setTextColor(Ui.TEXT);
        }

        recordPanel.setVisibility(rec || prep ? View.VISIBLE : View.GONE);
        emptyHint.setVisibility(!rec && !prep && shown.isEmpty() ? View.VISIBLE : View.GONE);

        if (rec) {
            long s = (System.currentTimeMillis() - RecorderService.startedAt()) / 1000;
            recTime.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
            recTarget.setText(cfg.targetLabel + " · " + cfg.targetPkg);
            String note = RecorderService.lastNote();
            recInfo.setText(RecorderService.lastSize() + " · " + RecorderService.lastQuality()
                    + (note.isEmpty() ? "" : "\n" + note));
        } else if (prep) {
            recTime.setText("准备中…");
            recTarget.setText(cfg.targetLabel);
            recInfo.setText("正在创建编码器");
        }
    }

    // ==================================================================
    // 应用列表
    // ==================================================================

    private void loadApps() {
        final boolean withSystem = showSystem;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> out = new ArrayList<>();
                String err = null;
                try {
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> list = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : list) {
                        if (ai.packageName.equals(getPackageName())) continue;
                        if (!withSystem && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        CharSequence label = pm.getApplicationLabel(ai);
                        AppEntry e = new AppEntry(label == null ? ai.packageName : label.toString(),
                                ai.packageName, ai.uid);
                        e.targetSdk = ai.targetSdkVersion;
                        e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        try {
                            e.icon = pm.getApplicationIcon(ai);
                        } catch (Throwable ignored) { }
                        out.add(e);
                    }
                } catch (Throwable t) {
                    err = t.toString();
                }
                Collections.sort(out, new Comparator<AppEntry>() {
                    @Override public int compare(AppEntry a, AppEntry b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(out);
                        applyFilter();
                        if (allApps.isEmpty() && ferr != null) {
                            emptyHint.setText("读取应用列表失败：" + ferr);
                            emptyHint.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "app-list").start();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (AppEntry e : allApps) {
            if (q.isEmpty() || e.label.toLowerCase(Locale.US).contains(q)
                    || e.pkg.toLowerCase(Locale.US).contains(q)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty() && !allApps.isEmpty()) {
            emptyHint.setText("没有匹配的应用");
        } else if (allApps.isEmpty()) {
            emptyHint.setText("正在读取应用列表…");
        }
        refreshState();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }

        @Override public Object getItem(int position) { return shown.get(position); }

        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = Ui.hbox(MainActivity.this);
                row.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 9),
                        Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 9));
                ImageView iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 42), Ui.dp(MainActivity.this, 42));
                ilp.setMargins(0, 0, Ui.dp(MainActivity.this, 12), 0);
                iv.setLayoutParams(ilp);
                iv.setTag("icon");
                row.addView(iv);

                LinearLayout col = Ui.vbox(MainActivity.this);
                col.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView l1 = Ui.tv(MainActivity.this, "", 15f, Ui.TEXT, false);
                l1.setTag("label");
                TextView l2 = Ui.tv(MainActivity.this, "", 11.5f, Ui.DIM, false);
                l2.setTag("sub");
                col.addView(l1);
                col.addView(l2);
                row.addView(col);

                TextView badge = Ui.tv(MainActivity.this, "", 11f, Ui.DIM, false);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 5),
                        Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 5));
                badge.setTag("badge");
                row.addView(badge);
            }

            AppEntry e = shown.get(position);
            ImageView iv = (ImageView) row.findViewWithTag("icon");
            TextView l1 = (TextView) row.findViewWithTag("label");
            TextView l2 = (TextView) row.findViewWithTag("sub");
            TextView badge = (TextView) row.findViewWithTag("badge");

            if (e.icon != null) iv.setImageDrawable(e.icon);
            else iv.setImageDrawable(null);
            l1.setText(e.label);
            l2.setText(e.pkg + "  ·  uid " + e.uid + "  ·  targetSdk " + e.targetSdk);

            boolean selected = e.pkg.equals(cfg.targetPkg);
            boolean cap = e.captureLikely();
            badge.setText(cap ? "可内录" : "可能静音");
            badge.setTextColor(cap ? Ui.OK : Ui.WARN);
            badge.setBackground(Ui.bg(cap ? 0xFF12291B : 0xFF2A2113, 8f, MainActivity.this));
            row.setBackground(selected
                    ? Ui.strokeBg(0xFF16233A, 12f, Ui.ACCENT, 1.2f, MainActivity.this)
                    : Ui.bg(Color.TRANSPARENT, 0f, MainActivity.this));
            return row;
        }
    }

    // ==================================================================
    // 录制流程
    // ==================================================================

    private void beginRecord() {
        if (cfg.targetPkg == null || cfg.targetPkg.isEmpty()) {
            toast("请先在列表中选择要录制的应用");
            return;
        }
        List<String> need = missingPerms();
        if (!need.isEmpty() && !askedPerms) {
            askedPerms = true;
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        if (cfg.audioEnabled
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            cfg.audioEnabled = false;
            Prefs.saveConfig(this, cfg);
            toast("未授予录音权限，本次仅录画面");
        }
        askProjection();
    }

    private void askProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("系统不支持录屏");
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Throwable t) {
            toast("无法请求录屏授权：" + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        refreshPermChip();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            new AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("还没有录音权限")
                    .setMessage("只录画面仍然可用，但录不到目标应用的声音。\n"
                            + "请到系统设置 → 应用 → 应用定向录屏 → 权限，打开「录音」。")
                    .setPositiveButton("去设置", (d, w) -> openAppSettings())
                    .setNegativeButton("仅录画面", (d, w) -> beginRecord())
                    .show();
            return;
        }
        beginRecord();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            toast("已取消录屏授权");
            return;
        }
        Intent svc = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                .putExtra(RecorderService.EXTRA_CONFIG, cfg.toBundle());
        try {
            startForegroundService(svc);
        } catch (Throwable t) {
            toast("启动录制服务失败：" + t);
            return;
        }
        final boolean hide = cfg.hideSelf;
        final boolean launch = cfg.launchTarget;
        // 先趁本界面还在前台把目标应用拉起来（Android 10+ 后台启动 Activity 会被拦），再退到后台
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (launch) launchTarget();
                if (hide) ui.postDelayed(new Runnable() {
                    @Override public void run() { finish(); }
                }, 700);
            }
        }, 900);
        refreshState();
    }

    private void launchTarget() {
        try {
            Intent li = getPackageManager().getLaunchIntentForPackage(cfg.targetPkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(li);
                return;
            }
        } catch (Throwable ignored) { }
        toast("该应用没有启动入口，请手动切换过去");
    }

    private void stopRecording() {
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
        try {
            startService(i);
        } catch (Throwable t) {
            toast("停止失败：" + t);
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { showResult(); }
        }, 3500);
    }

    private void showResult() {
        String err = RecorderService.lastError();
        String file = RecorderService.lastFile();
        if (err != null && !err.isEmpty()) {
            new AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("录制失败")
                    .setMessage(err)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (file == null || file.isEmpty()) return;
        Prefs.addHistory(this, file);
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("录制完成")
                .setMessage("已保存到：\n" + file
                        + "\n\n" + RecorderService.lastNote())
                .setPositiveButton("好", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
