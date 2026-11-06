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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.Analysis;
import io.github.yy7511.locallutbox.core.ColorProfile;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageBackup;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Renderer;
import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.core.Watermark;
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
    private Lut[] luts = new Lut[0];
    private Adjust adj;
    private int srcSpace = ColorSpace.SRGB;
    private boolean syncing;
    private boolean compareHeld;
    private int renderToken;
    private boolean loadedOnce;
    private boolean applyingHistory;

    private final ArrayList<State> undoStack = new ArrayList<State>();
    private final ArrayList<State> redoStack = new ArrayList<State>();
    private ImageView btnUndo;
    private ImageView btnRedo;

    /** Snapshot of all editable state for one image. */
    private static final class State {
        final Adjust adj;
        final ArrayList<String> lutIds;

        State(Adjust adj, ArrayList<String> lutIds) {
            this.adj = adj.copy();
            this.lutIds = new ArrayList<String>(lutIds);
        }
    }

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
    private TextView tabAdjust;
    private TextView tabLut;
    private TextView lutHint;

    private HistogramView histogram;
    private VectorscopeView vectorscope;
    private View analysisStrip;
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
        tabAdjust = (TextView) findViewById(R.id.tabAdjust);
        tabLut = (TextView) findViewById(R.id.tabLut);
        lutHint = (TextView) findViewById(R.id.lutHint);
        analysisStrip = findViewById(R.id.analysisStrip);
        btnUndo = (ImageView) findViewById(R.id.btnUndo);
        btnRedo = (ImageView) findViewById(R.id.btnRedo);
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
        applyGraph();
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
                public void onStartTrackingTouch(SeekBar seekBar) {
                    pushHistory();
                }

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
    }

    private void selectTab(int which) {
        paneAdjust.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        paneLut.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        Ui.setTab(tabAdjust, which == 0);
        Ui.setTab(tabLut, which == 1);
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
                    pushHistory();
                    adj.reset();
                    syncSliders();
                    scheduleRender();
                    Ui.toast(EditorActivity.this, "参数已重置");
                }
            }
        });
        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undo();
            }
        });
        btnRedo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redo();
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
        applyGraph();
        updateHint();
    }

    /** Shows the histogram / vectorscope strip according to the user's choice. */
    private void applyGraph() {
        int choice = Store.graphChoice(this);
        boolean show = choice != 3 && !fullscreen;
        analysisStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        histogram.setVisibility(choice == 1 ? View.GONE : View.VISIBLE);
        vectorscope.setVisibility(choice == 0 ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams vp = (LinearLayout.LayoutParams) vectorscope.getLayoutParams();
        if (choice == 1) {
            vp.width = 0;
            vp.weight = 1;
        } else {
            vp.width = Ui.dp(this, 88);
            vp.weight = 0;
        }
        vectorscope.setLayoutParams(vp);
    }

    /** LUT preview edge in dp; level 0/1/2 -> 56 / 72 / 92. */
    private int thumbDp() {
        int level = Store.lutThumbSize(this);
        return level == 0 ? 56 : (level == 2 ? 92 : 72);
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
                addLut(lutId);
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
        undoStack.clear();
        redoStack.clear();
        updateUndoRedoButtons();
        title.setText(entry.name);
        pageInfo.setText((index + 1) + " / " + entries.size());
        loadLutStack();
        syncSliders();
        buildLutChips();
        imgPreview.setImageDrawable(null);
        compareHeld = false;
        updateHint();
        original = null;
        rendered = null;

        final int token = ++renderToken;
        final Store.ImageEntry en = entry;
        progressTag.setVisibility(View.VISIBLE);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                String err = null;
                int space = ColorSpace.SRGB;
                try {
                    ImageBackup.ensure(EditorActivity.this, en);
                    java.io.File backup = ImageBackup.existing(en);
                    if (backup != null) {
                        space = ColorProfile.detect(backup);
                    } else {
                        space = ColorProfile.detect(getContentResolver(), Uri.parse(en.uri));
                    }
                    b = ImageUtil.decodeEntry(EditorActivity.this, en, 1400);
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
                        Store.saveImages(EditorActivity.this, entries);
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

    /** Loads the applied LUTs in order and drops ids that were deleted from the library. */
    private void loadLutStack() {
        if (entry.lutIds == null) {
            entry.lutIds = new ArrayList<String>();
        }
        for (int i = entry.lutIds.size() - 1; i >= 0; i--) {
            if (LutManager.find(this, entry.lutIds.get(i)) == null) {
                entry.lutIds.remove(i);
            }
        }
        luts = LutManager.loadAll(this, entry.lutIds);
    }

    /** “A → B（2 层）” summary used in the LUT pane. */
    private String stackLabel() {
        if (entry.lutIds == null || entry.lutIds.isEmpty()) {
            return "当前未叠加 LUT";
        }
        StringBuilder sb = new StringBuilder("叠加 ").append(entry.lutIds.size()).append(" 层：");
        for (int i = 0; i < entry.lutIds.size(); i++) {
            Store.LutEntry e = LutManager.find(this, entry.lutIds.get(i));
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(e == null ? "?" : e.name);
        }
        return sb.toString();
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

    /** Captures the current state onto the undo stack; clears the redo stack. */
    private void pushHistory() {
        if (applyingHistory || adj == null || entry == null) {
            return;
        }
        undoStack.add(new State(adj, entry.lutIds));
        redoStack.clear();
        updateUndoRedoButtons();
    }

    /** Restores a snapshot: adjustments, LUT stack, sliders and chips. */
    private void applyState(State s) {
        applyingHistory = true;
        try {
            adj.set(s.adj);
            entry.lutIds = new ArrayList<String>(s.lutIds);
            loadLutStack();
            syncSliders();
            buildLutChips();
            scheduleRender();
        } finally {
            applyingHistory = false;
        }
        updateUndoRedoButtons();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.add(new State(adj, entry.lutIds));
        State s = undoStack.remove(undoStack.size() - 1);
        applyState(s);
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.add(new State(adj, entry.lutIds));
        State s = redoStack.remove(redoStack.size() - 1);
        applyState(s);
    }

    private void updateUndoRedoButtons() {
        // Icon-only controls: enabled state is conveyed by tint + alpha.
        tintIcon(btnUndo, !undoStack.isEmpty());
        tintIcon(btnRedo, !redoStack.isEmpty());
    }

    private void tintIcon(ImageView view, boolean active) {
        view.setEnabled(active);
        view.setAlpha(active ? 1f : 0.45f);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(active ? Ui.ACCENT : Ui.MUTED));
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
        final Lut[] ls = luts;
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
                    out = Renderer.render(src, ls, a, 1400, srcSpace);
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
        lutHint.setText(stackLabel());
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
        int order = lutId.length() == 0 ? (entry.lutIds.isEmpty() ? 1 : 0)
                : entry.lutIds.indexOf(lutId) + 1;
        boolean selected = lutId.length() == 0 ? entry.lutIds.isEmpty() : order > 0;
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER_HORIZONTAL);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        int thumb = Ui.dp(this, thumbDp());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumb + Ui.dp(this, 10),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), 0);
        chip.setLayoutParams(lp);
        chip.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_thumb);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(iv, new FrameLayout.LayoutParams(thumb, thumb));
        chip.addView(holder, new LinearLayout.LayoutParams(thumb, thumb));
        if (lutId.length() > 0 && order > 0) {
            TextView badge = Ui.text(this, String.valueOf(order), 10, Ui.ON_ACCENT);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundResource(R.drawable.bg_accent_badge);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 16), Ui.dp(this, 16), Gravity.TOP | Gravity.LEFT);
            blp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), 0, 0);
            holder.addView(badge, blp);
        }

        TextView tv = Ui.text(this, name, 9, selected ? Ui.ACCENT : Ui.TEXT);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 4);
        chip.addView(tv, tlp);

        if (lutId.length() == 0) {
            ThumbLoader.load(iv, entry.uri, entry.file, thumb);
        } else {
            iv.setTag("chipthumb:" + lutId);
        }

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLut(lutId);
            }
        });
        chip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (lutId.length() > 0) {
                    showLutDetail(lutId);
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
        final Store.ImageEntry en = entry;
        final ArrayList<Store.LutEntry> luts = Store.luts(this);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap source = null;
                try {
                    source = ImageUtil.decodeEntry(EditorActivity.this, en, 320);
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
                            : renderChipThumb(en.uri, square, le.id);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, thumbDp()) + Ui.dp(this, 10), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), 0);
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

    /** Short press: adds the LUT on top of the stack, or removes it if it is already applied. */
    private void toggleLut(String id) {
        pushHistory();
        if (id.length() == 0) {
            entry.lutIds.clear();
            Store.setLastLutId(this, "");
            loadLutStack();
            buildLutChips();
            scheduleRender();
            Ui.toast(this, "已清空叠加，恢复原图");
            return;
        }
        if (entry.lutIds.contains(id)) {
            entry.lutIds.remove(id);
            Ui.toast(this, "已移除该 LUT");
        } else {
            entry.lutIds.add(id);
            Store.setLastLutId(this, id);
            Ui.toast(this, "已叠加第 " + entry.lutIds.size() + " 层");
        }
        loadLutStack();
        buildLutChips();
        scheduleRender();
    }

    private void addLut(String id) {
        if (entry.lutIds.contains(id)) {
            Ui.toast(this, "此 LUT 已在叠加中");
            return;
        }
        pushHistory();
        entry.lutIds.add(id);
        Store.setLastLutId(this, id);
        loadLutStack();
        buildLutChips();
        scheduleRender();
        Ui.toast(this, "已叠加第 " + entry.lutIds.size() + " 层");
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

    /** Values collected from the export dialog. */
    private static final class ExportOpts {
        int sizeMode;
        int longEdge;
        int ratio;
        int quality;
        int version;
        boolean watermark;
        String watermarkText;
        int watermarkPos;
        int watermarkOpacity;
        boolean toFolder;
    }

    private interface Choice {
        void onPick(int index);
    }

    private void saveWithDialog() {
        if (entry == null) {
            return;
        }
        final View dv = getLayoutInflater().inflate(R.layout.dialog_export, null);
        final LinearLayout sizeRow = (LinearLayout) dv.findViewById(R.id.sizeRow);
        final EditText sizeValue = (EditText) dv.findViewById(R.id.sizeValue);
        final TextView qualityValue = (TextView) dv.findViewById(R.id.qualityValue);
        final SeekBar qualityBar = (SeekBar) dv.findViewById(R.id.qualityBar);
        final EditText versionInput = (EditText) dv.findViewById(R.id.versionInput);
        final TextView nameHint = (TextView) dv.findViewById(R.id.nameHint);
        final CheckBox wmCheck = (CheckBox) dv.findViewById(R.id.watermarkCheck);
        final EditText wmInput = (EditText) dv.findViewById(R.id.watermarkInput);
        final LinearLayout wmPosRow = (LinearLayout) dv.findViewById(R.id.watermarkPosRow);
        final TextView destLabel = (TextView) dv.findViewById(R.id.destLabel);
        final LinearLayout destRow = (LinearLayout) dv.findViewById(R.id.destRow);

        final int[] mode = {Store.exportSizeMode(this)};
        final int[] wmPos = {Store.watermarkPos(this)};
        final int[] dest = {0};
        final int nextVersion = Math.max(1, entry.version + 1);
        final String base = ImageUtil.baseName(entry.name);

        versionInput.setText(String.valueOf(nextVersion));
        qualityBar.setProgress(Store.exportQuality(this));
        qualityValue.setText(String.valueOf(Store.exportQuality(this)));
        wmCheck.setChecked(Store.watermarkOn(this));
        wmInput.setText(Store.watermarkText(this));

        final Runnable syncSize = new Runnable() {
            @Override
            public void run() {
                if (mode[0] == Store.SIZE_ORIGINAL) {
                    sizeValue.setVisibility(View.GONE);
                } else {
                    sizeValue.setVisibility(View.VISIBLE);
                    if (mode[0] == Store.SIZE_LONG_EDGE) {
                        sizeValue.setHint("长边像素，例如 2048");
                        sizeValue.setText(String.valueOf(Store.exportLongEdge(EditorActivity.this)));
                    } else {
                        sizeValue.setHint("比例 %（1-100）");
                        sizeValue.setText(String.valueOf(Store.exportRatio(EditorActivity.this)));
                    }
                }
            }
        };
        syncSize.run();
        fillChoiceRow(sizeRow, new String[]{"原图尺寸", "长边像素", "缩放比例"}, mode[0], new Choice() {
            @Override
            public void onPick(int index) {
                mode[0] = index;
                syncSize.run();
            }
        });
        sizeValue.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int v = number(sizeValue, 0);
                if (v <= 0) {
                    return;
                }
                if (mode[0] == Store.SIZE_LONG_EDGE) {
                    Store.setExportLongEdge(EditorActivity.this, v);
                } else if (mode[0] == Store.SIZE_RATIO) {
                    Store.setExportRatio(EditorActivity.this, v);
                }
            }
        });

        qualityBar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int q = Math.max(1, progress);
                qualityValue.setText(String.valueOf(q));
                Store.setExportQuality(EditorActivity.this, q);
            }
        });

        wmInput.setVisibility(wmCheck.isChecked() ? View.VISIBLE : View.GONE);
        wmPosRow.setVisibility(wmCheck.isChecked() ? View.VISIBLE : View.GONE);
        fillChoiceRow(wmPosRow, new String[]{"右下", "左下", "右上", "左上", "居中"}, wmPos[0], new Choice() {
            @Override
            public void onPick(int index) {
                wmPos[0] = index;
            }
        });
        wmCheck.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                int vis = isChecked ? View.VISIBLE : View.GONE;
                wmInput.setVisibility(vis);
                wmPosRow.setVisibility(vis);
            }
        });

        final boolean hasOut = Store.auto(this).outTree != null && Store.auto(this).outTree.length() > 0;
        if (hasOut) {
            fillChoiceRow(destRow, new String[]{"相册", "输出文件夹"}, 0, new Choice() {
                @Override
                public void onPick(int index) {
                    dest[0] = index;
                }
            });
        } else {
            destLabel.setVisibility(View.GONE);
            destRow.setVisibility(View.GONE);
        }

        versionInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateNameHint(nameHint, base, versionInput);
            }
        });
        updateNameHint(nameHint, base, versionInput);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导出图片")
                .setView(dv)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ExportOpts o = new ExportOpts();
                        o.sizeMode = mode[0];
                        o.longEdge = Store.exportLongEdge(EditorActivity.this);
                        o.ratio = Store.exportRatio(EditorActivity.this);
                        o.quality = Math.max(1, qualityBar.getProgress());
                        o.version = Math.max(1, number(versionInput, nextVersion));
                        o.watermark = wmCheck.isChecked();
                        o.watermarkText = wmInput.getText().toString().trim();
                        o.watermarkPos = wmPos[0];
                        o.watermarkOpacity = Store.watermarkOpacity(EditorActivity.this);
                        o.toFolder = hasOut && dest[0] == 1;
                        if (o.watermark && o.watermarkText.length() == 0) {
                            Ui.toast(EditorActivity.this, "请输入水印文字");
                            return;
                        }
                        Store.setExportSizeMode(EditorActivity.this, o.sizeMode);
                        Store.setExportQuality(EditorActivity.this, o.quality);
                        Store.setWatermarkOn(EditorActivity.this, o.watermark);
                        Store.setWatermarkText(EditorActivity.this, o.watermarkText);
                        Store.setWatermarkPos(EditorActivity.this, o.watermarkPos);
                        dialog.dismiss();
                        doSave(o);
                    }
                });
            }
        });
        dialog.show();
    }

    private int number(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void updateNameHint(TextView hint, String base, EditText input) {
        int v = Math.max(1, number(input, 1));
        hint.setText(base + "_LUT_v" + v + ".jpg");
    }

    private TextView choiceChip(LinearLayout row, String label) {
        TextView t = Ui.text(this, label, 11, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(t, lp);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void fillChoiceRow(LinearLayout row, String[] labels, int selected, final Choice choice) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = choiceChip(row, labels[i]);
            styleChoice(chip, i == selected);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int k = 0; k < row.getChildCount(); k++) {
                        styleChoice((TextView) row.getChildAt(k), k == index);
                    }
                    choice.onPick(index);
                }
            });
        }
    }

    private void styleChoice(TextView t, boolean selected) {
        t.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        t.setTextColor(selected ? Ui.ACCENT : Ui.MUTED);
        t.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** Long-edge limit for the chosen export size; 0 = keep the decoded size. */
    private static int targetLongEdge(ExportOpts o, Bitmap bmp) {
        int max = Math.max(bmp.getWidth(), bmp.getHeight());
        if (o.sizeMode == Store.SIZE_LONG_EDGE) {
            return Math.min(o.longEdge, max);
        }
        if (o.sizeMode == Store.SIZE_RATIO) {
            return Math.max(1, Math.round(max * o.ratio / 100f));
        }
        return 0;
    }

    private void doSave(final ExportOpts o) {
        final String name = ImageUtil.baseName(entry.name) + "_LUT_v" + o.version + ".jpg";
        entry.version = Math.max(entry.version, o.version);
        Store.saveImages(this, entries);
        final String uriStr = entry.uri;
        final Store.ImageEntry expEntry = entry;
        final Lut[] stack = luts;
        final Adjust savedAdj = adj.copy();
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
                    ImageBackup.ensure(EditorActivity.this, expEntry);
                    try {
                        full = ImageUtil.decodeEntry(EditorActivity.this, expEntry, 0);
                        out = Renderer.render(full, stack, savedAdj, targetLongEdge(o, full), srcSpace);
                    } catch (OutOfMemoryError oom) {
                        if (full != null) {
                            full.recycle();
                            full = null;
                        }
                        out = null;
                        reduced = true;
                        full = ImageUtil.decodeEntry(EditorActivity.this, expEntry, 4096);
                        int target = targetLongEdge(o, full);
                        if (target <= 0 || target > 4096) {
                            target = 4096;
                        }
                        out = Renderer.render(full, stack, savedAdj, target, srcSpace);
                    }
                    if (out == null) {
                        throw new Exception("渲染失败");
                    }
                    if (o.watermark) {
                        Watermark.draw(out, o.watermarkText, o.watermarkPos, o.watermarkOpacity);
                    }
                    java.io.File exifFile = ImageBackup.existing(expEntry);
                    Uri exifUri = Uri.parse(uriStr);
                    String note = reduced ? "（内存不足，已按长边 4096 导出）" : "";
                    String info = "v" + o.version + " · 长边 " + Math.max(out.getWidth(), out.getHeight())
                            + " · 质量 " + o.quality + (o.watermark ? " · 水印" : "");
                    if (o.toFolder) {
                        String tree = Store.auto(EditorActivity.this).outTree;
                        if (exifFile != null) {
                            ImageUtil.saveToTree(getContentResolver(), tree, name, out, o.quality, exifFile);
                        } else {
                            ImageUtil.saveToTree(getContentResolver(), tree, name, out, o.quality, exifUri);
                        }
                        msg = "已保存到自动输出文件夹（" + info + " · EXIF 已保留）" + note;
                    } else {
                        if (exifFile != null) {
                            ImageUtil.saveToGallery(EditorActivity.this, out, name, o.quality, exifFile);
                        } else {
                            ImageUtil.saveToGallery(EditorActivity.this, out, name, o.quality, exifUri);
                        }
                        msg = "已保存到相册 Pictures/LUT暗房（" + info + " · EXIF 已保留）" + note;
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
        applyGraph();
        if (loadedOnce && entry != null) {
            int before = luts.length;
            loadLutStack();
            if (luts.length != before) {
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
