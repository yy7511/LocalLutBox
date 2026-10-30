package io.github.yy7511.locallutbox.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.yy7511.locallutbox.R;
import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Renderer;
import io.github.yy7511.locallutbox.core.Store;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fullscreen LUT inspection: renders the LUT over a preview bitmap, lists the
 * parameters captured at import time and optionally applies it.
 */
public final class LutDetail {

    private static final int MAX_DIM = 1600;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private LutDetail() {
    }

    /**
     * @param base       preview source (must stay alive while the dialog is open)
     * @param onApply    null hides the apply button (library mode)
     * @param sourceNote label of what the preview is rendered from
     */
    public static void show(final Activity a, final Store.LutEntry entry, final Bitmap base,
                            final Adjust adj, final Runnable onApply, String sourceNote) {
        final Lut lut = LutManager.load(a, entry.id);
        if (lut == null) {
            Ui.toast(a, "LUT 读取失败");
            return;
        }
        showPreview(a, entry.name, metaOf(entry, lut), entry.params, lut, base, adj, onApply, sourceNote);
    }

    private static String metaOf(Store.LutEntry entry, Lut lut) {
        String meta = entry.label.length() > 0 ? entry.label : (lut.kind == Lut.KIND_1D
                ? "1D · " + lut.size : "3D · " + lut.size + "³");
        String params = entry.params == null ? "" : entry.params;
        if (lut.kind == Lut.KIND_3D && !params.contains("输入域")) {
            meta += String.format(Locale.US, " · 输入域 %.3f–%.3f", lut.domainMin, lut.domainMax);
        }
        meta += " · 目标空间 " + ColorSpace.name(lut.space);
        return meta;
    }

    /**
     * Generic variant: any title/meta/params with an optional LUT (null = adjustments only).
     */
    public static void showPreview(final Activity a, final String title, final String meta,
                                   final String params, final Lut lut, final Bitmap base,
                                   final Adjust adj, final Runnable onApply, String sourceNote) {
        showPreview(a, title, meta, params, lut, base, adj, onApply, sourceNote, ColorSpace.SRGB);
    }

    /** Same as above, but the base bitmap was decoded from the given source colour space. */
    public static void showPreview(final Activity a, final String title, final String meta,
                                   final String params, final Lut lut, final Bitmap base,
                                   final Adjust adj, final Runnable onApply, String sourceNote,
                                   final int srcSpace) {
        showStack(a, title, meta, params, lut == null ? new Lut[0] : new Lut[]{lut}, base, adj,
                onApply, sourceNote, srcSpace);
    }

    /** Renders a whole LUT stack (bottom layer first) over the base bitmap. */
    public static void showStack(final Activity a, final String title, final String meta,
                                 final String params, final Lut[] luts, final Bitmap base,
                                 final Adjust adj, final Runnable onApply, String sourceNote,
                                 final int srcSpace) {
        final Dialog dialog = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        dialog.setContentView(root);

        final ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("LUT 全屏预览");
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView info = new TextView(a);
        info.setTextSize(11);
        info.setTextColor(0xFFF3F6FA);
        info.setLineSpacing(Ui.dp(a, 2), 1f);
        info.setBackgroundColor(0xB3000000);
        info.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        info.setText(buildInfo(title, meta, params, sourceNote));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.TOP;
        ilp.setMargins(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), 0);
        root.addView(info, ilp);

        final TextView loading = new TextView(a);
        loading.setText("渲染中…");
        loading.setTextSize(12);
        loading.setTextColor(0xFF6EC8F4);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.CENTER;
        root.addView(loading, llp);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = Ui.dp(a, 26);
        root.addView(bar, blp);

        TextView close = button(a, "关闭", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        if (onApply != null) {
            TextView apply = button(a, "套用此 LUT", true);
            apply.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    onApply.run();
                }
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.rightMargin = Ui.dp(a, 12);
            bar.addView(apply, alp);
        }
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View.OnClickListener dismiss = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        };
        image.setOnClickListener(dismiss);
        info.setOnClickListener(dismiss);

        dialog.show();

        final Bitmap[] rendered = new Bitmap[1];
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                image.setImageDrawable(null);
                Bitmap b = rendered[0];
                if (b != null && !b.isRecycled() && b != base) {
                    b.recycle();
                }
                rendered[0] = null;
            }
        });

        if (base == null || base.isRecycled()) {
            loading.setVisibility(View.GONE);
            Ui.toast(a, "预览图不可用");
        } else {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap result = null;
                    try {
                        result = Renderer.render(base, luts, adj == null ? new Adjust() : adj, MAX_DIM, srcSpace);
                    } catch (Throwable ignored) {
                        // source can be recycled while the dialog is closing
                    }
                    final Bitmap out = result;
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading.setVisibility(View.GONE);
                            if (!dialog.isShowing()) {
                                if (out != null) {
                                    out.recycle();
                                }
                                return;
                            }
                            if (out == null) {
                                Ui.toast(a, "预览渲染失败");
                                return;
                            }
                            rendered[0] = out;
                            image.setImageBitmap(out);
                        }
                    });
                }
            });
        }
    }

    private static TextView button(Activity a, String label, boolean primary) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(a, 18), Ui.dp(a, 9), Ui.dp(a, 18), Ui.dp(a, 9));
        if (primary) {
            t.setBackgroundResource(R.drawable.bg_btn);
            t.setTextColor(Ui.ON_ACCENT);
            t.getPaint().setFakeBoldText(true);
        } else {
            t.setBackgroundResource(R.drawable.bg_btn_ghost);
            t.setTextColor(0xFFF2F5F8);
        }
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private static String buildInfo(String title, String meta, String params, String sourceNote) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append(meta);
        sb.append('\n').append("参数：")
                .append(params != null && params.length() > 0 ? params : "未记录");
        if (sourceNote != null && sourceNote.length() > 0) {
            sb.append('\n').append("预览源：").append(sourceNote);
        }
        sb.append('\n').append("点击图片关闭");
        return sb.toString();
    }
}
