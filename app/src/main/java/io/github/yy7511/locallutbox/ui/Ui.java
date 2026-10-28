package io.github.yy7511.locallutbox.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.yy7511.locallutbox.R;

import java.util.Locale;

/** Small UI factory helpers (framework widgets only, no AppCompat). */
public final class Ui {

    public static int BG = 0xFF050809;
    public static int TEXT = 0xFFEEF6FC;
    public static int MUTED = 0xFF93AEC2;
    public static int ACCENT = 0xFF6EC8F4;
    public static int ON_ACCENT = 0xFF04141C;
    public static int TEAL = 0xFF4FD3C7;
    public static int RED = 0xFFF27A76;
    public static int STROKE = 0xFF223342;

    public static int SCOPE_BG = 0xFF080A0D;
    public static int SCOPE_GRID = 0x33FFFFFF;
    public static int SCOPE_GRID_SOFT = 0x1CFFFFFF;
    public static int SCOPE_AXIS = 0x66FFFFFF;
    public static int SCOPE_SKIN = 0x446EC8F4;

    private Ui() {
    }

    /** Keeps programmatic colours in step with the XML palette (see values / values-night). */
    public static void applyPalette(boolean dark) {
        if (dark) {
            BG = 0xFF050809;
            TEXT = 0xFFEEF6FC;
            MUTED = 0xFF93AEC2;
            ACCENT = 0xFF6EC8F4;
            ON_ACCENT = 0xFF04141C;
            TEAL = 0xFF4FD3C7;
            RED = 0xFFF27A76;
            STROKE = 0xFF223342;
            SCOPE_BG = 0xFF080A0D;
            SCOPE_GRID = 0x33FFFFFF;
            SCOPE_GRID_SOFT = 0x1CFFFFFF;
            SCOPE_AXIS = 0x66FFFFFF;
            SCOPE_SKIN = 0x446EC8F4;
        } else {
            BG = 0xFFF6FAFD;
            TEXT = 0xFF0D1E29;
            MUTED = 0xFF4F6B80;
            ACCENT = 0xFF177095;
            ON_ACCENT = 0xFFFFFFFF;
            TEAL = 0xFF0F7D8A;
            RED = 0xFFC0392B;
            STROKE = 0xFFD6E7F3;
            SCOPE_BG = 0xFFF5F8FB;
            SCOPE_GRID = 0x24000000;
            SCOPE_GRID_SOFT = 0x12000000;
            SCOPE_AXIS = 0x55000000;
            SCOPE_SKIN = 0x55177095;
        }
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView pill(Context c, String label, boolean primary) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(primary ? R.drawable.bg_btn : R.drawable.bg_btn_ghost);
        t.setTextColor(primary ? ON_ACCENT : TEXT);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static void setTab(TextView t, boolean selected) {
        t.setTextColor(selected ? ACCENT : MUTED);
        t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    public static String signed(float v) {
        String s = String.format(Locale.US, "%.2f", v);
        return v > 0 ? "+" + s : s;
    }

    public static final class Slider {
        public final LinearLayout root;
        public final TextView label;
        public final TextView value;
        public final SeekBar bar;

        Slider(LinearLayout root, TextView label, TextView value, SeekBar bar) {
            this.root = root;
            this.label = label;
            this.value = value;
            this.bar = bar;
        }
    }

    /** Adds a labeled slider row to a vertical container. */
    public static Slider addSlider(LinearLayout parent, String name, int max, int progress, String valueText) {
        Context c = parent.getContext();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(c, 3), 0, 0);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text(c, name, 11, MUTED);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = text(c, valueText, 11, TEXT);
        value.setTypeface(Typeface.MONOSPACE);
        value.setGravity(Gravity.END);
        head.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TapSeekBar bar = new TapSeekBar(c);
        bar.setMax(max);
        bar.setDefaultProgress(progress);
        bar.setProgress(progress);
        bar.setSplitTrack(false);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setPadding(dp(c, 6), 0, dp(c, 6), 0);

        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 26)));
        parent.addView(root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Slider(root, label, value, bar);
    }

    /**
     * SeekBar with tap-to-nudge: tapping left of the thumb decreases one step,
     * right increases one step, double-tapping resets to the default value.
     * Dragging still works exactly like a normal SeekBar.
     */
    public static final class TapSeekBar extends SeekBar {

        private static final int DOUBLE_TAP_MS = 320;

        private int step = 1;
        private int defaultProgress;
        private final int slop;
        private final int thumbTolerance;
        private final int doubleTapSlop;

        private long lastTapAt;
        private float lastTapX;
        private float downX;
        private float downY;
        private MotionEvent downEvent;
        private boolean delegating;
        private boolean moved;

        public TapSeekBar(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            thumbTolerance = dp(c, 14);
            doubleTapSlop = dp(c, 48);
        }

        public void setStep(int step) {
            this.step = Math.max(1, step);
        }

        public void setDefaultProgress(int progress) {
            this.defaultProgress = progress;
        }

        private float thumbCenter() {
            int padL = getPaddingLeft();
            int padR = getPaddingRight();
            Drawable t = getThumb();
            int thumbW = t == null ? 0 : t.getIntrinsicWidth();
            float span = Math.max(1f, getWidth() - padL - padR - thumbW);
            float ratio = getMax() <= 0 ? 0f : Math.min(1f, getProgress() / (float) getMax());
            return padL + thumbW / 2f + span * ratio;
        }

        private void recycleDown() {
            if (downEvent != null) {
                downEvent.recycle();
                downEvent = null;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
                moved = false;
                recycleDown();
                downEvent = MotionEvent.obtain(ev);
                delegating = Math.abs(downX - thumbCenter()) <= thumbTolerance;
                if (delegating) {
                    return super.onTouchEvent(ev);
                }
                return true;
            }
            if (delegating) {
                boolean handled = super.onTouchEvent(ev);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    delegating = false;
                    recycleDown();
                }
                return handled;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (!moved && (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop)) {
                    moved = true;
                    delegating = true;
                    if (downEvent != null) {
                        super.onTouchEvent(downEvent);
                    }
                    recycleDown();
                    return super.onTouchEvent(ev);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                recycleDown();
                handleTap(ev.getX());
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                recycleDown();
            }
            return true;
        }

        private void handleTap(float x) {
            long now = SystemClock.uptimeMillis();
            boolean onThumb = Math.abs(x - thumbCenter()) <= thumbTolerance;
            boolean doubleTap = onThumb && lastTapAt != 0 && now - lastTapAt <= DOUBLE_TAP_MS
                    && Math.abs(x - lastTapX) <= doubleTapSlop;
            if (doubleTap) {
                lastTapAt = 0;
                setProgress(defaultProgress);
                return;
            }
            if (onThumb) {
                // Tapping the thumb only arms the double-tap reset, it must not nudge the value.
                lastTapAt = now;
                lastTapX = x;
                return;
            }
            lastTapAt = 0;
            int dir = x < thumbCenter() ? -1 : 1;
            setProgress(Math.max(0, Math.min(getMax(), getProgress() + dir * step)));
        }
    }

    /** Convenience adapter so activities only override onProgressChanged. */
    public static abstract class SeekAdapter implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
