package io.github.yy7511.locallutbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.Analysis;
import io.github.yy7511.locallutbox.core.ColorProfile;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Renderer;
import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.HistogramView;
import io.github.yy7511.locallutbox.ui.LutDetail;
import io.github.yy7511.locallutbox.ui.ThumbLoader;
import io.github.yy7511.locallutbox.ui.Ui;
import io.github.yy7511.locallutbox.ui.VectorscopeView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditorActivity extends BaseActivity {

    private final ArrayList<Store.ImageEntry> entries = new ArrayList<Store.ImageEntry>();
    private int index;
    private Store.ImageEntry entry;
    private Bitmap original;
    private Bitmap rendered;
    private Lut lut;
    private Adjust adj;
    private int srcSpace = ColorSpace.SRGB;
    private boolean syncing;
    private boolean compareHeld;
    private int renderToken;
    private boolean loadedOnce;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView imgPreview;
    private TextView title;
    private TextView pageInfo;
    private TextView progressTag;
    private TextView compareHint;
    private LinearLayout adjustList;
    private LinearLayout lutChips;
    private View paneAdjust;
    private LinearLayout paneLut;
    private LinearLayout paneAnalysis;
    private TextView tabAdjust;
    private TextView tabLut;
    private TextView tabAnalysis;

    private HistogramView histogram;
    private VectorscopeView vectorscope;
    private View editorBar;
    private View pagerBar;
    private View panel;
    private boolean fullscreen;
    private int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private long touchDownAt;

    private final Runnable compareOn = new Runnable() {
        @Override
        public void run() {
            if (original != null && rendered != null) {
                compareHeld = true;
                imgPreview.setImageBitmap(original);
                compareHint.setText("原图");
            }
        }
    };

    private final Ui.Slider[] sliders = new Ui.Slider[9];
    private final HashMap<String, Bitmap> chipThumbCache = new HashMap<String, Bitmap>();
    private int chipToken;
    private final String[] names = {"曝光", "对比度", "高光", "阴影", "饱和度",
            "自然饱和度", "色温", "色调", "清晰度"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        imgPreview = (ImageView) findViewById(R.id.imgPreview);
        title = (TextView) findViewById(R.id.title);
        pageInfo = (TextView) findViewById(R.id.pageInfo);
        progressTag = (TextView) findViewById(R.id.progressTag);
        compareHint = (TextView) findViewById(R.id.compareHint);
        adjustList = (LinearLayout) findViewById(R.id.adjustList);
        lutChips = (LinearLayout) findViewById(R.id.lutChips);
        paneAdjust = findViewById(R.id.paneAdjust);
        paneLut = (LinearLayout) findViewById(R.id.paneLut);
        paneAnalysis = (LinearLayout) findViewById(R.id.paneAnalysis);
        tabAdjust = (TextView) findViewById(R.id.tabAdjust);
        tabLut = (TextView) findViewById(R.id.tabLut);
        tabAnalysis = (TextView) findViewById(R.id.tabAnalysis);
        histogram = (HistogramView) findViewById(R.id.histogram);
        vectorscope = (VectorscopeView) findViewById(R.id.vectorscope);
        editorBar = findViewById(R.id.editorBar);
        pagerBar = findViewById(R.id.pagerBar);
        panel = findViewById(R.id.panel);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        entries.addAll(Store.images(this));
        if (entries.isEmpty()) {
            Ui.toast(this, "请先导入图片");
            finish();
            return;
        }
        index = getIntent().getIntExtra("index", 0);
        if (index < 0) {
            index = 0;
        }
        if (index >= entries.size()) {
            index = entries.size() - 1;
        }

        buildSliders();
        bindTabs();
        bindActions();
        selectTab(0);
        loadCurrent();
    }

    private void buildSliders() {
        TextView hint = Ui.text(this, "参数仅作用于当前图片，可随时重置", 9, Ui.MUTED);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        adjustList.addView(hint);
        for (int i = 0; i < 9; i++) {
            sliders[i] = Ui.addSlider(adjustList, names[i], 200, 100, "0.00");
            final int which = i;
            sliders[i].bar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (syncing) {
                        return;
                    }
                    float v = progressToValue(which, progress);
                    setField(which, v);
                    sliders[which].value.setText(Ui.signed(v));
                    scheduleRender();
                }
            });
        }
    }

    private void bindTabs() {
        tabAdjust.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(0);
            }
        });
        tabLut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(1);
            }
        });
        tabAnalysis.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(2);
            }
        });
    }

    private void selectTab(int which) {
        paneAdjust.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        paneLut.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        paneAnalysis.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        Ui.setTab(tabAdjust, which == 0);
        Ui.setTab(tabLut, which == 1);
        Ui.setTab(tabAnalysis, which == 2);
    }

    private void bindActions() {
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(-1);
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(1);
            }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveWithDialog();
            }
        });
        findViewById(R.id.btnReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adj != null) {
                    adj.reset();
                    syncSliders();
                    scheduleRender();
                    Ui.toast(EditorActivity.this, "参数已重置");
                }
            }
        });
        imgPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    touchDownAt = SystemClock.uptimeMillis();
                    main.postDelayed(compareOn, 200);
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getX() - touchDownX) > touchSlop
                            || Math.abs(event.getY() - touchDownY) > touchSlop) {
                        main.removeCallbacks(compareOn);
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    main.removeCallbacks(compareOn);
                    boolean still = Math.abs(event.getX() - touchDownX) <= touchSlop
                            && Math.abs(event.getY() - touchDownY) <= touchSlop;
                    long held = SystemClock.uptimeMillis() - touchDownAt;
                    if (compareHeld) {
                        compareHeld = false;
                        if (rendered != null && !rendered.isRecycled()) {
                            imgPreview.setImageBitmap(rendered);
                        }
                        updateHint();
                    } else if (still && held < 260) {
                        toggleFullscreen();
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    main.removeCallbacks(compareOn);
                    if (compareHeld) {
                        compareHeld = false;
                        if (rendered != null && !rendered.isRecycled()) {
                            imgPreview.setImageBitmap(rendered);
                        }
                        updateHint();
                    }
                }
                return false;
            }
        });
    }

    /** Single tap toggles the immersive preview; the chrome is hidden while fullscreen. */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        int vis = fullscreen ? View.GONE : View.VISIBLE;
        editorBar.setVisibility(vis);
        pagerBar.setVisibility(vis);
        panel.setVisibility(vis);
        updateHint();
    }

    private void updateHint() {
        compareHint.setText(fullscreen
                ? "点击图片退出全屏 · 按住对比原图"
                : "点击图片全屏 · 按住对比原图");
    }

    /** Fullscreen inspection of a LUT: parameters + preview with the current edits applied. */
    private void showLutDetail(final String lutId) {
        Store.LutEntry e = LutManager.find(this, lutId);
        if (e == null) {
            Ui.toast(this, "LUT 不存在");
            return;
        }
        if (original == null) {
            Ui.toast(this, "图片尚未加载完成");
            return;
        }
        LutDetail.show(this, e, original, adj, new Runnable() {
            @Override
            public void run() {
                selectLut(lutId);
            }
        }, "当前照片 + 当前参数");
    }

    private void loadCurrent() {
        if (index < 0 || index >= entries.size()) {
            finish();
            return;
        }
        entry = entries.get(index);
        if (entry.adj == null) {
            entry.adj = new Adjust();
        }
        adj = entry.adj;
        title.setText(entry.name);
        pageInfo.setText((index + 1) + " / " + entries.size());
        lut = entry.lutId == null || entry.lutId.length() == 0 ? null : LutManager.load(this, entry.lutId);
        if (lut == null) {
            entry.lutId = "";
        }
        syncSliders();
        buildLutChips();
        imgPreview.setImageDrawable(null);
        compareHeld = false;
        updateHint();
        original = null;
        rendered = null;

        final int token = ++renderToken;
        final String uri = entry.uri;
        progressTag.setVisibility(View.VISIBLE);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                String err = null;
                int space = ColorSpace.SRGB;
                try {
                    space = ColorProfile.detect(getContentResolver(), Uri.parse(uri));
                    b = ImageUtil.decodeUri(getContentResolver(), Uri.parse(uri), 1400);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Bitmap fb = b;
                final String fe = err;
                final int fsp = space;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != renderToken) {
                            return;
                        }
                        if (fb == null) {
                            progressTag.setVisibility(View.GONE);
                            Ui.toast(EditorActivity.this, "图片加载失败：" + fe);
                            return;
                        }
                        original = fb;
                        srcSpace = fsp;
                        loadedOnce = true;
                        renderNow();
                    }
                });
            }
        });
    }

    private void syncSliders() {
        syncing = true;
        for (int i = 0; i < 9; i++) {
            float v = getField(i);
            int p = valueToProgress(i, v);
            sliders[i].bar.setProgress(p);
            sliders[i].value.setText(Ui.signed(v));
        }
        syncing = false;
    }

    private int valueToProgress(int which, float v) {
        int p = which == 0 ? 100 + Math.round(v * 50f) : 100 + Math.round(v * 100f);
        return Math.max(0, Math.min(200, p));
    }

    private float progressToValue(int which, int progress) {
        return which == 0 ? (progress - 100) / 50f : (progress - 100) / 100f;
    }

    private float getField(int which) {
        switch (which) {
            case 0:
                return adj.exposure;
            case 1:
                return adj.contrast;
            case 2:
                return adj.highlights;
            case 3:
                return adj.shadows;
            case 4:
                return adj.saturation;
            case 5:
                return adj.vibrance;
            case 6:
                return adj.temperature;
            case 7:
                return adj.tint;
            default:
                return adj.clarity;
        }
    }

    private void setField(int which, float v) {
        switch (which) {
            case 0:
                adj.exposure = v;
                break;
            case 1:
                adj.contrast = v;
                break;
            case 2:
                adj.highlights = v;
                break;
            case 3:
                adj.shadows = v;
                break;
            case 4:
                adj.saturation = v;
                break;
            case 5:
                adj.vibrance = v;
                break;
            case 6:
                adj.temperature = v;
                break;
            case 7:
                adj.tint = v;
                break;
            default:
                adj.clarity = v;
                break;
        }
    }

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            renderNow();
        }
    };

    private void scheduleRender() {
        main.removeCallbacks(renderTask);
        main.postDelayed(renderTask, 70);
    }

    private void renderNow() {
        final Bitmap src = original;
        if (src == null || src.isRecycled() || adj == null) {
            return;
        }
        final int token = ++renderToken;
        final Adjust a = adj.copy();
        final Lut l = lut;
        progressTag.setVisibility(View.VISIBLE);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                int[] h = Analysis.newHistogram();
                int[] vc = Analysis.newVecGrid();
                int[] vr = new int[Analysis.VEC_N * Analysis.VEC_N];
                int[] vg = new int[Analysis.VEC_N * Analysis.VEC_N];
                int[] vb = new int[Analysis.VEC_N * Analysis.VEC_N];
                try {
                    out = Renderer.render(src, l, a, 1400, srcSpace);
                    if (out != null) {
                        Analysis.compute(out, 220, h, vc, vr, vg, vb);
                    }
                } catch (OutOfMemoryError | Exception ignored) {
                }
                final Bitmap res = out;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        progressTag.setVisibility(View.GONE);
                        if (token != renderToken || res == null) {
                            if (res != null && res != rendered) {
                                res.recycle();
                            }
                            return;
                        }
                        Bitmap old = rendered;
                        rendered = res;
                        imgPreview.setImageBitmap(res);
                        if (old != null && old != res && !old.isRecycled()) {
                            old.recycle();
                        }
                        if (!compareHeld) {
                            compareHint.setText("按住图片对比原图");
                        }
                        histogram.setHistogram(h);
                        vectorscope.setData(vc, vr, vg, vb);
                    }
                });
            }
        });
    }

    private void buildLutChips() {
        lutChips.removeAllViews();
        addLutChip("原图", "");
        ArrayList<Store.LutEntry> luts = Store.luts(this);
        for (int i = 0; i < luts.size(); i++) {
            Store.LutEntry e = luts.get(i);
            addLutChip(e.name, e.id);
        }
        if (luts.isEmpty()) {
            addManageChip("导入 LUT");
        } else {
            addManageChip("管理 LUT");
        }
        fillChipThumbs();
    }

    private void addLutChip(String name, final String lutId) {
        boolean selected = entry.lutId == null ? lutId.length() == 0 : entry.lutId.equals(lutId);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 76),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6));
        chip.setLayoutParams(lp);
        chip.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_thumb);
        chip.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 62)));

        TextView tv = Ui.text(this, name, 9, selected ? Ui.ACCENT : Ui.TEXT);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 4);
        chip.addView(tv, tlp);

        if (lutId.length() == 0) {
            ThumbLoader.load(iv, entry.uri, 72);
        } else {
            iv.setTag("chipthumb:" + lutId);
        }

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lutId.length() == 0) {
                    selectLut("");
                } else {
                    showLutDetail(lutId);
                }
            }
        });
        chip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (lutId.length() > 0) {
                    selectLut(lutId);
                    Ui.toast(EditorActivity.this, "已套用");
                    return true;
                }
                return false;
            }
        });
        lutChips.addView(chip);
    }

    /** Renders every LUT chip from the photo being edited, so the preview matches the real picture. */
    private void fillChipThumbs() {
        final int token = ++chipToken;
        final String uri = entry.uri;
        final ArrayList<Store.LutEntry> luts = Store.luts(this);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap source = null;
                try {
                    source = ImageUtil.decodeUri(getContentResolver(), Uri.parse(uri), 320);
                } catch (Exception ignored) {
                }
                Bitmap square = source == null ? null : squareOf(source, 160);
                for (int i = 0; i < luts.size(); i++) {
                    if (token != chipToken) {
                        break;
                    }
                    final Store.LutEntry le = luts.get(i);
                    final Bitmap thumb = square == null
                            ? LutManager.thumb(EditorActivity.this, le.id)
                            : renderChipThumb(uri, square, le.id);
                    if (thumb == null) {
                        continue;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != chipToken) {
                                return;
                            }
                            View v = lutChips.findViewWithTag("chipthumb:" + le.id);
                            if (v instanceof ImageView) {
                                ((ImageView) v).setImageBitmap(thumb);
                            }
                        }
                    });
                }
            }
        });
    }

    private Bitmap renderChipThumb(String uri, Bitmap square, String lutId) {
        String key = uri + "|" + lutId;
        synchronized (chipThumbCache) {
            Bitmap hit = chipThumbCache.get(key);
            if (hit != null && !hit.isRecycled()) {
                return hit;
            }
        }
        Lut l = LutManager.load(this, lutId);
        if (l == null) {
            return null;
        }
        Bitmap out = Renderer.render(square, l, new Adjust(), 0, srcSpace);
        if (out == null) {
            return null;
        }
        synchronized (chipThumbCache) {
            if (chipThumbCache.size() > 48) {
                chipThumbCache.clear();
            }
            chipThumbCache.put(key, out);
        }
        return out;
    }

    private static Bitmap squareOf(Bitmap src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int s = Math.min(w, h);
        Bitmap crop = (s == w && s == h) ? src : Bitmap.createBitmap(src, (w - s) / 2, (h - s) / 2, s, s);
        Bitmap out = s == size ? crop : Bitmap.createScaledBitmap(crop, size, size, true);
        if (crop != src && crop != out) {
            crop.recycle();
        }
        if (src != out && src != crop) {
            src.recycle();
        }
        return out;
    }

    private void addManageChip(String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 76),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6));
        chip.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_lut);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        chip.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView tv = Ui.text(this, label, 9, Ui.MUTED);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        chip.addView(tv);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(EditorActivity.this, LutLibraryActivity.class));
            }
        });
        lutChips.addView(chip);
    }

    private void selectLut(String id) {
        entry.lutId = id;
        Store.setLastLutId(this, id);
        lut = id.length() == 0 ? null : LutManager.load(this, id);
        buildLutChips();
        scheduleRender();
    }

    private void go(int delta) {
        int next = index + delta;
        if (next < 0 || next >= entries.size()) {
            return;
        }
        Store.saveImages(this, entries);
        main.removeCallbacks(renderTask);
        index = next;
        loadCurrent();
    }

    private void saveWithDialog() {
        if (entry == null) {
            return;
        }
        final Store.AutoCfg cfg = Store.auto(this);
        boolean hasOut = cfg.outTree != null && cfg.outTree.length() > 0;
        if (hasOut) {
            new AlertDialog.Builder(this)
                    .setTitle("保存图片")
                    .setItems(new String[]{"保存到相册（Pictures/LUT暗房）", "保存到自动输出文件夹"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    doSave(which == 1);
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            doSave(false);
        }
    }

    private void doSave(final boolean toFolder) {
        final String name = ImageUtil.baseName(entry.name) + "_LUT.jpg";
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在导出…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Exception ignored) {
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                Bitmap full = null;
                Bitmap out = null;
                boolean reduced = false;
                try {
                    try {
                        full = ImageUtil.decodeUri(getContentResolver(), Uri.parse(entry.uri), 0);
                        out = Renderer.render(full, lut, adj.copy(), 0, srcSpace);
                    } catch (OutOfMemoryError oom) {
                        if (full != null) {
                            full.recycle();
                            full = null;
                        }
                        out = null;
                        reduced = true;
                        full = ImageUtil.decodeUri(getContentResolver(), Uri.parse(entry.uri), 4096);
                        out = Renderer.render(full, lut, adj.copy(), 4096, srcSpace);
                    }
                    if (out == null) {
                        throw new Exception("渲染失败");
                    }
                    Uri exifSource = Uri.parse(entry.uri);
                    String note = reduced ? "（内存不足，已按长边 4096 导出）" : "";
                    if (toFolder) {
                        String tree = Store.auto(EditorActivity.this).outTree;
                        ImageUtil.saveToTree(getContentResolver(), tree, name, out, 100, exifSource);
                        msg = "已保存到自动输出文件夹（原图尺寸 · EXIF 已保留）" + note;
                    } else {
                        ImageUtil.saveToGallery(EditorActivity.this, out, name, 100, exifSource);
                        msg = "已保存到相册 Pictures/LUT暗房（原图尺寸 · EXIF 已保留）" + note;
                    }
                } catch (Exception e) {
                    msg = "保存失败：" + e.getMessage();
                } catch (OutOfMemoryError e) {
                    msg = "内存不足，无法导出原尺寸";
                }
                if (full != null) {
                    full.recycle();
                }
                if (out != null) {
                    out.recycle();
                }
                final String fmsg = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        Ui.toast(EditorActivity.this, fmsg);
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loadedOnce && entry != null) {
            lut = entry.lutId == null || entry.lutId.length() == 0 ? null : LutManager.load(this, entry.lutId);
            if (lut == null && entry.lutId != null && entry.lutId.length() > 0) {
                entry.lutId = "";
                scheduleRender();
            }
            buildLutChips();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!entries.isEmpty()) {
            Store.saveImages(this, entries);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
