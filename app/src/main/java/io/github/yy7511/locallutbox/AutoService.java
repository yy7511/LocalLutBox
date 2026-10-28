package io.github.yy7511.locallutbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;

import io.github.yy7511.locallutbox.core.ColorProfile;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Renderer;
import io.github.yy7511.locallutbox.core.Store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/** Foreground service that watches a SAF folder and LUT-grades newly added images. */
public class AutoService extends Service {

    public static final String ACTION_START = "io.github.yy7511.locallutbox.START";
    public static final String ACTION_STOP = "io.github.yy7511.locallutbox.STOP";
    public static final String ACTION_SCAN = "io.github.yy7511.locallutbox.SCAN";
    public static final String EXTRA_FORCE_ALL = "force_all";

    private static final String CHANNEL_ID = "auto_lut";
    private static final int NOTIF_ID = 8801;
    private static final long INTERVAL = 6000L;
    private Bitmap largeIcon;

    private HandlerThread thread;
    private Handler worker;
    private Handler mainHandler;
    private ContentObserver observer;
    private String observedTree = "";
    private boolean scanning;
    private volatile boolean forceAll;
    private boolean loopStarted;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scan();
            if (Store.autoRunning(AutoService.this)) {
                worker.postDelayed(this, INTERVAL);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("lut-auto-scan");
        thread.start();
        worker = new Handler(thread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_FORCE_ALL, false)) {
            forceAll = true;
        }
        startForegroundSafe();
        Store.setAutoRunning(this, true);
        registerObserver();
        if (!loopStarted) {
            loopStarted = true;
            worker.post(scanRunnable);
        } else {
            worker.removeCallbacks(scanRunnable);
            worker.post(scanRunnable);
        }
        return START_STICKY;
    }

    private void startForegroundSafe() {
        try {
            startForeground(NOTIF_ID, buildNotification("监听中"));
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "自动 LUT 处理",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setDescription("文件夹监听与自动调色");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void registerObserver() {
        String tree = Store.auto(this).inTree;
        if (tree == null || tree.length() == 0) {
            return;
        }
        if (observer != null && tree.equals(observedTree)) {
            return;
        }
        unregisterObserver();
        try {
            Uri treeUri = Uri.parse(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            observer = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    triggerScanSoon();
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    triggerScanSoon();
                }
            };
            getContentResolver().registerContentObserver(children, true, observer);
            observedTree = tree;
        } catch (Throwable t) {
            observer = null;
            observedTree = "";
        }
    }

    private void unregisterObserver() {
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable ignored) {
            }
            observer = null;
            observedTree = "";
        }
    }

    private void triggerScanSoon() {
        worker.removeCallbacks(scanRunnable);
        worker.postDelayed(scanRunnable, 600);
    }

    private void scan() {
        if (scanning) {
            return;
        }
        scanning = true;
        try {
            Store.AutoCfg cfg = Store.auto(this);
            if (!cfg.running) {
                stopSelf();
                return;
            }
            if (cfg.inTree.length() == 0) {
                Store.log(this, "未设置输入文件夹");
                return;
            }
            ContentResolver cr = getContentResolver();
            ArrayList<ImageUtil.Doc> docs;
            try {
                docs = ImageUtil.listDocs(cr, cfg.inTree);
            } catch (Exception e) {
                Store.log(this, "读取文件夹失败：" + e.getMessage());
                return;
            }

            Set<String> processed = Store.processed(this);
            String seed = Store.seedTree(this);
            boolean force = forceAll;
            forceAll = false;

            if (!force && !cfg.inTree.equals(seed)) {
                int added = 0;
                for (int i = 0; i < docs.size(); i++) {
                    if (processed.add(key(docs.get(i)))) {
                        added++;
                    }
                }
                Store.saveProcessed(this, processed);
                Store.setSeedTree(this, cfg.inTree);
                Store.log(this, "已记录 " + added + " 个已有文件，等待新增");
                notifyState("监听中 · 现有文件跳过");
                return;
            }
            if (!cfg.inTree.equals(seed)) {
                Store.setSeedTree(this, cfg.inTree);
            }

            Lut lut = cfg.lutId == null || cfg.lutId.length() == 0 ? null : LutManager.load(this, cfg.lutId);
            int done = 0;
            int failed = 0;
            for (int i = 0; i < docs.size() && done + failed < 300; i++) {
                ImageUtil.Doc doc = docs.get(i);
                if (isOutput(doc.name)) {
                    continue;
                }
                String k = key(doc);
                if (processed.contains(k)) {
                    continue;
                }
                boolean ok = processDoc(cfg, doc, lut);
                processed.add(k);
                Store.saveProcessed(this, processed);
                if (ok) {
                    done++;
                } else {
                    failed++;
                }
            }
            if (done > 0 || failed > 0) {
                String msg = "本轮完成 " + done + " 张" + (failed > 0 ? "，失败 " + failed + " 张" : "");
                Store.log(this, msg);
                notifyState("已完成 " + done + " 张");
            }
        } catch (Throwable t) {
            Store.log(this, "扫描异常：" + t.getMessage());
        } finally {
            scanning = false;
        }
    }

    private boolean processDoc(Store.AutoCfg cfg, ImageUtil.Doc doc, Lut lut) {
        String outName = ImageUtil.baseName(doc.name) + "_LUT.jpg";
        Bitmap src = null;
        Bitmap out = null;
        int maxDim = cfg.maxDim < 0 ? 0 : cfg.maxDim;
        boolean reduced = false;
        try {
            Uri docUri = ImageUtil.docUri(cfg.inTree, doc.id);
            int space = cfg.convertSpace ? ColorProfile.detect(getContentResolver(), docUri) : ColorSpace.SRGB;
            try {
                src = ImageUtil.decodeUri(getContentResolver(), docUri, maxDim);
                out = Renderer.render(src, lut, cfg.adj, maxDim, space);
            } catch (OutOfMemoryError oom) {
                if (src != null) {
                    src.recycle();
                    src = null;
                }
                out = null;
                reduced = true;
                maxDim = 3000;
                src = ImageUtil.decodeUri(getContentResolver(), docUri, maxDim);
                out = Renderer.render(src, lut, cfg.adj, maxDim, space);
            }
            if (out == null) {
                throw new IOException("渲染失败");
            }
            String tree = cfg.outTree == null || cfg.outTree.length() == 0 ? cfg.inTree : cfg.outTree;
            ImageUtil.saveToTree(getContentResolver(), tree, outName, out, cfg.quality, docUri);
            String spaceNote = "";
            if (cfg.convertSpace) {
                int target = lut == null ? ColorSpace.SRGB : ColorSpace.clampSpace(lut.space);
                if (space != target) {
                    spaceNote = "（" + ColorSpace.name(space) + " → " + ColorSpace.name(target)
                            + " → sRGB）";
                }
            }
            Store.log(this, "✓ " + doc.name + " → " + outName + spaceNote
                    + (reduced ? "（内存不足，按长边 3000 处理）" : ""));
            return true;
        } catch (Throwable t) {
            Store.log(this, "✗ " + doc.name + "：" + t.getMessage());
            return false;
        } finally {
            if (src != null && src != out) {
                src.recycle();
            }
            if (out != null) {
                out.recycle();
            }
        }
    }

    private static boolean isOutput(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith("_lut.jpg") || lower.endsWith("_lut.jpeg") || lower.endsWith("_lut.png");
    }

    private static String key(ImageUtil.Doc d) {
        return d.name + "|" + d.modified;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, AutoActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pflags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pflags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pflags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notify);
        try {
            b.setColor(getColor(R.color.accent));
        } catch (Throwable ignored) {
        }
        if (largeIcon == null) {
            try {
                largeIcon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            } catch (Throwable ignored) {
            }
        }
        if (largeIcon != null) {
            b.setLargeIcon(largeIcon);
        }
        b.setContentTitle("LocalLutBox · 自动处理");
        b.setContentText(text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setContentIntent(pi);
        return b.build();
    }

    private void notifyState(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Store.setAutoRunning(this, false);
        unregisterObserver();
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }
}
