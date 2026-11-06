package io.github.yy7511.locallutbox.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Lut;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Renderer;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Async thumbnail loader with memory cache. */
public final class ThumbLoader {

    private static final LruCache<String, Bitmap> CACHE;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        CACHE = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private ThumbLoader() {
    }

    public static void load(final ImageView view, final String uri, final int sizeDp) {
        load(view, uri, "", sizeDp);
    }

    /** Prefers the app-private backup file, falling back to the SAF URI. */
    public static void load(final ImageView view, final String uri, final String file, final int sizeDp) {
        if (uri == null || uri.length() == 0) {
            view.setImageDrawable(null);
            return;
        }
        final Context app = view.getContext().getApplicationContext();
        final int px = Ui.dp(app, sizeDp);
        final String key = (file != null && file.length() > 0 ? file : uri) + "@" + px;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(key);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                try {
                    Bitmap full = decodeSource(app, uri, file, px * 2);
                    out = squareThumb(full, px);
                    if (out != null) {
                        CACHE.put(key, out);
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                }
                deliver(view, key, out);
            }
        });
    }

    /**
     * Thumbnail of the graded photo (adjustments + LUT stack), so the main grid
     * previews what the editor shows instead of the untouched original. The whole
     * frame is kept (fit, not cropped) so the photo keeps its real aspect ratio.
     */
    public static void loadGraded(final ImageView view, final String uri, final String file,
                                  final Adjust adj, final List<String> lutIds, final int sizeDp) {
        if (uri == null || uri.length() == 0) {
            view.setImageDrawable(null);
            return;
        }
        final Context app = view.getContext().getApplicationContext();
        final int px = Ui.dp(app, sizeDp);
        final String key = (file != null && file.length() > 0 ? file : uri) + "@" + px + "#"
                + (adj == null ? "" : adj.encode())
                + "#" + (lutIds == null ? "" : TextUtils.join(",", lutIds));
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(key);
        final Adjust adjCopy = adj == null ? new Adjust() : adj.copy();
        final String[] ids = lutIds == null ? new String[0] : lutIds.toArray(new String[0]);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap out = null;
                try {
                    Bitmap full = decodeSource(app, uri, file, px * 2);
                    if (full != null) {
                        if (ids.length == 0 && adjCopy.isNeutral()) {
                            out = fitThumb(full, px);
                        } else {
                            Lut[] luts = LutManager.loadAll(app, java.util.Arrays.asList(ids));
                            out = Renderer.render(full, luts, adjCopy, px, ColorSpace.SRGB);
                            if (!full.isRecycled()) {
                                full.recycle();
                            }
                        }
                        if (out != null) {
                            CACHE.put(key, out);
                        }
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                }
                deliver(view, key, out);
            }
        });
    }

    /** Decodes from the app-private backup when available, else from the URI. */
    private static Bitmap decodeSource(Context app, String uri, String file, int maxDim) throws Exception {
        File backup = file != null && file.length() > 0 ? new File(file) : null;
        if (backup != null && backup.isFile() && backup.length() > 0) {
            return ImageUtil.decodeFile(backup, maxDim);
        }
        return ImageUtil.decodeUri(app.getContentResolver(), android.net.Uri.parse(uri), maxDim);
    }

    /** Scales the whole frame down to {@code maxDim} on the long edge; never crops. */
    private static Bitmap fitThumb(Bitmap full, int maxDim) {
        if (full == null) {
            return null;
        }
        int w = full.getWidth();
        int h = full.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDim) {
            return full;
        }
        float s = maxDim / (float) max;
        int tw = Math.max(1, Math.round(w * s));
        int th = Math.max(1, Math.round(h * s));
        Bitmap out = Bitmap.createScaledBitmap(full, tw, th, true);
        if (out != full) {
            full.recycle();
        }
        return out;
    }

    /** Center-crops to a square and scales down to {@code px}; recycles the input. */
    private static Bitmap squareThumb(Bitmap full, int px) {
        if (full == null) {
            return null;
        }
        int w = full.getWidth();
        int h = full.getHeight();
        int s = Math.min(w, h);
        Bitmap crop = (s == w && s == h) ? full : Bitmap.createBitmap(full, (w - s) / 2, (h - s) / 2, s, s);
        Bitmap out = s == px ? crop : Bitmap.createScaledBitmap(crop, px, px, true);
        if (crop != full && crop != out) {
            crop.recycle();
        }
        if (full != out) {
            full.recycle();
        }
        return out;
    }

    private static void deliver(final ImageView view, final String key, final Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            }
        });
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
