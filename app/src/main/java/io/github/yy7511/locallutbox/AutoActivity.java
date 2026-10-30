package io.github.yy7511.locallutbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.ColorProfile;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.LutDetail;
import io.github.yy7511.locallutbox.ui.LutPicker;
import io.github.yy7511.locallutbox.ui.Ui;

import java.util.ArrayList;
import java.util.Locale;

/** Folder auto-processing settings: input/output tree, fixed LUT, base adjustments, service toggle. */
public class AutoActivity extends BaseActivity {

    private static final int REQ_IN = 201;
    private static final int REQ_OUT = 202;
    private static final int REQ_NOTIF = 203;

    private Store.AutoCfg cfg;
    private boolean syncing;

    private TextView inPath;
    private TextView outPath;
    private TextView autoLutName;
    private TextView btnToggle;
    private TextView logText;
    private TextView notifState;
    private TextView battState;
    private TextView btnSpace;
    private TextView btnSize;
    private TextView btnDelete;
    private LinearLayout adjustList;

    private int pendingAction; // 0 = none, 1 = start watching, 2 = process existing files
    private Bitmap previewBase;

    private final Ui.Slider[] sliders = new Ui.Slider[4];
    private final String[] names = {"曝光", "对比度", "饱和度", "清晰度"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            main.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto);

        inPath = (TextView) findViewById(R.id.inPath);
        outPath = (TextView) findViewById(R.id.outPath);
        autoLutName = (TextView) findViewById(R.id.autoLutName);
        btnToggle = (TextView) findViewById(R.id.btnToggle);
        logText = (TextView) findViewById(R.id.logText);
        notifState = (TextView) findViewById(R.id.notifState);
        battState = (TextView) findViewById(R.id.battState);
        btnSpace = (TextView) findViewById(R.id.btnSpace);
        btnSize = (TextView) findViewById(R.id.btnSize);
        btnDelete = (TextView) findViewById(R.id.btnDelete);
        adjustList = (LinearLayout) findViewById(R.id.autoAdjustList);

        cfg = Store.auto(this);
        buildSliders();
        bindActions();
        syncUi();
    }

    private void buildSliders() {
        for (int i = 0; i < 4; i++) {
            Ui.Slider s = Ui.addSlider(adjustList, names[i], 200, 100, "0.00");
            sliders[i] = s;
            final int which = i;
            s.bar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (syncing) {
                        return;
                    }
                    float v = which == 0 ? (progress - 100) / 50f : (progress - 100) / 100f;
                    setField(which, v);
                    sliders[which].value.setText(Ui.signed(v));
                    Store.saveAuto(AutoActivity.this, cfg);
                }
            });
        }
    }

    private void bindActions() {
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnPickIn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree(REQ_IN);
            }
        });
        findViewById(R.id.btnPickOut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree(REQ_OUT);
            }
        });
        findViewById(R.id.btnClearOut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.outTree = "";
                Store.saveAuto(AutoActivity.this, cfg);
                syncUi();
            }
        });
        autoLutName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLut();
            }
        });
        btnSpace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.convertSpace = !cfg.convertSpace;
                Store.saveAuto(AutoActivity.this, cfg);
                syncUi();
                Ui.toast(AutoActivity.this, cfg.convertSpace
                        ? "已开启：按图片 ICC 转换到 LUT 目标空间"
                        : "已关闭色彩空间转换，统一按 sRGB 处理");
            }
        });
        btnSize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cfg.maxDim <= 0) {
                    cfg.maxDim = 3000;
                    cfg.quality = 95;
                } else if (cfg.maxDim >= 3000) {
                    cfg.maxDim = 2000;
                    cfg.quality = 95;
                } else {
                    cfg.maxDim = 0;
                    cfg.quality = 100;
                }
                Store.saveAuto(AutoActivity.this, cfg);
                syncUi();
                Ui.toast(AutoActivity.this, sizeLabel() + "，EXIF 均会保留");
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cfg.deleteSource) {
                    cfg.deleteSource = false;
                    Store.saveAuto(AutoActivity.this, cfg);
                    syncUi();
                    Ui.toast(AutoActivity.this, "已关闭：原文件会保留");
                    return;
                }
                new AlertDialog.Builder(AutoActivity.this)
                        .setTitle("处理后删除原文件")
                        .setMessage("开启后，只有成功输出 LUT 图片才会删除输入文件夹中的原文件。\n"
                                + "删除不可恢复；若输出文件夹与输入文件夹相同，原图与成品会混在一起，建议先把输出指向别处。")
                        .setPositiveButton("仍要开启", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                cfg.deleteSource = true;
                                Store.saveAuto(AutoActivity.this, cfg);
                                syncUi();
                                Ui.toast(AutoActivity.this, "已开启：输出成功后删除原文件");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        findViewById(R.id.btnProcessAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processAll();
            }
        });
        findViewById(R.id.btnPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                previewFirst();
            }
        });
        notifState.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNotification();
            }
        });
        battState.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBattery();
            }
        });
    }

    private void pickTree(int code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, code);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件夹选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (Throwable ignored) {
        }
        String tree = uri.toString();
        if (requestCode == REQ_IN) {
            boolean wasRunning = Store.autoRunning(this);
            cfg.inTree = tree;
            Store.saveAuto(this, cfg);
            Store.clearProcessed(this);
            stopServiceNow();
            if (wasRunning) {
                startServiceNow(false);
            }
        } else if (requestCode == REQ_OUT) {
            cfg.outTree = tree;
            Store.saveAuto(this, cfg);
        }
        syncUi();
    }

    private void pickLut() {
        LutPicker.show(this, cfg.lutIds, new LutPicker.Callback() {
            @Override
            public void onPick(ArrayList<String> ids) {
                cfg.lutIds = ids;
                Store.saveAuto(AutoActivity.this, cfg);
                syncUi();
                Ui.toast(AutoActivity.this, ids.isEmpty()
                        ? "已清空固定 LUT，仅用基础参数调色"
                        : "已选择 " + ids.size() + " 个 LUT，按顺序叠加");
            }
        });
    }

    private void toggleService() {
        if (cfg.running) {
            cfg.running = false;
            Store.saveAuto(this, cfg);
            stopServiceNow();
            syncUi();
            Ui.toast(this, "自动处理已停止");
            return;
        }
        if (cfg.inTree.length() == 0) {
            Ui.toast(this, "请先选择输入文件夹");
            return;
        }
        if (!ensureNotificationPermission(1)) {
            return;
        }
        startAuto();
    }

    private void startAuto() {
        cfg.running = true;
        Store.saveAuto(this, cfg);
        startServiceNow(false);
        syncUi();
        refreshPermissions();
        Ui.toast(this, "自动处理已开启，保持通知栏运行");
        if (!batteryExempt()) {
            askBattery();
        }
    }

    private void processAll() {
        if (cfg.inTree.length() == 0) {
            Ui.toast(this, "请先选择输入文件夹");
            return;
        }
        if (!ensureNotificationPermission(2)) {
            return;
        }
        runForceAll();
    }

    private void runForceAll() {
        Store.clearProcessed(this);
        if (!cfg.running) {
            cfg.running = true;
        }
        Store.saveAuto(this, cfg);
        startServiceNow(true);
        syncUi();
        refreshPermissions();
        Ui.toast(this, "已开始处理文件夹中已有图片");
        if (!batteryExempt()) {
            askBattery();
        }
    }

    /** Renders the first image in the input folder with the fixed LUT + base adjustments. */
    private void previewFirst() {
        if (cfg.inTree.length() == 0) {
            Ui.toast(this, "请先选择输入文件夹");
            return;
        }
        final String tree = cfg.inTree;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("读取文件夹首张图片…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                String name = null;
                String err = null;
                int detected = ColorSpace.SRGB;
                try {
                    ArrayList<ImageUtil.Doc> docs = ImageUtil.listDocs(getContentResolver(), tree);
                    for (int i = 0; i < docs.size(); i++) {
                        ImageUtil.Doc d = docs.get(i);
                        if (d.name != null && d.name.endsWith("_LUT.jpg")) {
                            continue;
                        }
                        name = d.name;
                        Uri docUri = ImageUtil.docUri(tree, d.id);
                        if (cfg.convertSpace) {
                            detected = ColorProfile.detect(getContentResolver(), docUri);
                        }
                        bmp = ImageUtil.decodeUri(getContentResolver(), docUri, 1400);
                        if (bmp != null) {
                            break;
                        }
                    }
                    if (bmp == null) {
                        err = "文件夹里没有可预览的图片";
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "无法读取文件夹" : e.getMessage();
                }
                final Bitmap fb = bmp;
                final String fn = name;
                final String fe = err;
                final int fsp = detected;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            if (fb != null) {
                                fb.recycle();
                            }
                            return;
                        }
                        pd.dismiss();
                        if (fb == null) {
                            Ui.toast(AutoActivity.this, fe);
                            return;
                        }
                        if (previewBase != null && !previewBase.isRecycled()) {
                            previewBase.recycle();
                        }
                        previewBase = fb;
                        Lut[] luts = LutManager.loadAll(AutoActivity.this, cfg.lutIds);
                        LutDetail.showStack(AutoActivity.this, fn,
                                luts.length == 0
                                        ? "无 LUT · 仅基础参数"
                                        : "叠加 " + luts.length + " 层：" + stackNames(),
                                adjustSummary(), luts, previewBase, cfg.adj, null,
                                "文件夹第一张图片", fsp);
                    }
                });
            }
        }).start();
    }

    /** “A → B” names of the fixed stack, in application order. */
    private String stackNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cfg.lutIds.size(); i++) {
            Store.LutEntry e = LutManager.find(this, cfg.lutIds.get(i));
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(e == null ? "?" : e.name);
        }
        return sb.toString();
    }

    /** Drops ids whose LUT was deleted from the library. */
    private void pruneLuts() {
        boolean changed = false;
        for (int i = cfg.lutIds.size() - 1; i >= 0; i--) {
            if (LutManager.find(this, cfg.lutIds.get(i)) == null) {
                cfg.lutIds.remove(i);
                changed = true;
            }
        }
        if (changed) {
            Store.saveAuto(this, cfg);
        }
    }

    private String adjustSummary() {
        return String.format(Locale.US, "曝光 %+.2f · 对比度 %+.0f · 饱和度 %+.0f · 清晰度 %+.0f",
                cfg.adj.exposure, cfg.adj.contrast * 100f, cfg.adj.saturation * 100f,
                cfg.adj.clarity * 100f);
    }

    private boolean notificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private boolean batteryExempt() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refreshPermissions() {
        boolean n = notificationsEnabled();
        notifState.setText(n ? "通知权限：已开启 · 可常驻通知栏"
                : "通知权限：未开启 · 点击开启，否则后台容易停止");
        notifState.setTextColor(n ? Ui.TEAL : Ui.ACCENT);
        boolean b = batteryExempt();
        battState.setText(b ? "电池优化：已豁免 · 可后台持续处理"
                : "电池优化：未豁免 · 点击申请，息屏后可能停止");
        battState.setTextColor(b ? Ui.TEAL : Ui.ACCENT);
    }

    /** Android 13+ runtime prompt; on older systems (incl. HarmonyOS 4) jumps to notification settings. */
    private void askNotification() {
        if (notificationsEnabled()) {
            Ui.toast(this, "通知权限已开启");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_DENIED) {
            pendingAction = 1;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        openNotificationSettings();
    }

    private void openNotificationSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
        } catch (Throwable t) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private void askBattery() {
        if (batteryExempt()) {
            Ui.toast(this, "已在电池优化白名单中");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("允许后台运行")
                .setMessage("自动处理需要在后台持续监视文件夹。请把本应用加入“忽略电池优化 / 不限制后台”名单，"
                        + "否则息屏或系统清理后可能停止处理新照片。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        try {
                            startActivity(i);
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            } catch (Throwable t2) {
                                openAppSettings();
                            }
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private boolean ensureNotificationPermission(int action) {
        if (notificationsEnabled()) {
            return true;
        }
        pendingAction = action;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            Ui.toast(this, "请允许通知权限，授予后会自动开启");
            return false;
        }
        // Notifications switched off system-wide (also the case on HarmonyOS): guide to settings.
        new AlertDialog.Builder(this)
                .setTitle("通知未开启")
                .setMessage("自动处理依赖前台常驻通知保活，通知被关闭时容易被系统停止。建议先在设置中开启通知。")
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openNotificationSettings();
                    }
                })
                .setNegativeButton("仍然开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (pendingAction == 2) {
                            runForceAll();
                        } else {
                            startAuto();
                        }
                        pendingAction = 0;
                    }
                })
                .show();
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        int action = pendingAction;
        pendingAction = 0;
        refreshPermissions();
        if (granted) {
            if (action == 2) {
                runForceAll();
            } else {
                startAuto();
            }
        } else {
            openNotificationSettings();
        }
    }

    private void startServiceNow(boolean forceAll) {
        Intent intent = new Intent(this, AutoService.class);
        intent.setAction(forceAll ? AutoService.ACTION_SCAN : AutoService.ACTION_START);
        if (forceAll) {
            intent.putExtra(AutoService.EXTRA_FORCE_ALL, true);
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            Ui.toast(this, "服务启动失败：" + t.getMessage());
        }
    }

    private void stopServiceNow() {
        try {
            stopService(new Intent(this, AutoService.class));
        } catch (Throwable ignored) {
        }
        Store.setAutoRunning(this, false);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncUi();
            }
        }, 300);
    }

    private void syncUi() {
        inPath.setText(cfg.inTree.length() == 0 ? "未选择" : ImageUtil.treeLabel(cfg.inTree));
        outPath.setText(cfg.outTree.length() == 0
                ? "未选择 · 输出到输入文件夹，文件名加 _LUT"
                : ImageUtil.treeLabel(cfg.outTree));
        pruneLuts();
        autoLutName.setText(cfg.lutIds.isEmpty()
                ? "无 LUT · 点击选择（可叠加）"
                : "叠加 " + cfg.lutIds.size() + " 层 · " + stackNames());
        boolean running = Store.autoRunning(this);
        btnSpace.setText(cfg.convertSpace ? "自动色彩空间转换：开" : "自动色彩空间转换：关");
        btnSpace.setTextColor(cfg.convertSpace ? Ui.ACCENT : Ui.MUTED);
        btnSpace.setBackgroundResource(cfg.convertSpace ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        btnSize.setText(sizeLabel());
        btnSize.setTextColor(cfg.maxDim <= 0 ? Ui.ACCENT : Ui.MUTED);
        btnSize.setBackgroundResource(cfg.maxDim <= 0 ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        btnDelete.setText(cfg.deleteSource ? "处理后删除原文件：开" : "处理后删除原文件：关");
        btnDelete.setTextColor(cfg.deleteSource ? Ui.ACCENT : Ui.MUTED);
        btnDelete.setBackgroundResource(cfg.deleteSource ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        btnToggle.setText(running ? "停止自动处理" : "开启自动处理");
        btnToggle.setBackgroundResource(running ? R.drawable.bg_btn_ghost : R.drawable.bg_btn);
        btnToggle.setTextColor(running ? Ui.TEXT : Ui.ON_ACCENT);
        syncing = true;
        for (int i = 0; i < 4; i++) {
            float v = getField(i);
            int p = i == 0 ? 100 + Math.round(v * 50f) : 100 + Math.round(v * 100f);
            p = Math.max(0, Math.min(200, p));
            sliders[i].bar.setProgress(p);
            sliders[i].value.setText(Ui.signed(v));
        }
        syncing = false;
    }

    private String sizeLabel() {
        if (cfg.maxDim <= 0) {
            return "输出尺寸：原图（不压缩）";
        }
        return "输出尺寸：长边 " + cfg.maxDim + " · 质量 " + cfg.quality;
    }

    private void refreshLog() {
        String log = Store.logText(this);
        logText.setText(log.length() == 0 ? "暂无记录" : log.trim());
    }

    private float getField(int which) {
        Adjust a = cfg.adj;
        switch (which) {
            case 0:
                return a.exposure;
            case 1:
                return a.contrast;
            case 2:
                return a.saturation;
            default:
                return a.clarity;
        }
    }

    private void setField(int which, float v) {
        switch (which) {
            case 0:
                cfg.adj.exposure = v;
                break;
            case 1:
                cfg.adj.contrast = v;
                break;
            case 2:
                cfg.adj.saturation = v;
                break;
            default:
                cfg.adj.clarity = v;
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = Store.auto(this);
        syncUi();
        refreshPermissions();
        refreshLog();
        main.removeCallbacks(logTick);
        main.postDelayed(logTick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(logTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (previewBase != null && !previewBase.isRecycled()) {
            previewBase.recycle();
        }
        previewBase = null;
    }
}
