package io.github.yy7511.locallutbox.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import io.github.yy7511.locallutbox.core.ImageUtil;

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
        if (uri == null || uri.length() == 0) {
            view.setImageDrawable(null);
            return;
        }
        final Context app = view.getContext().getApplicationContext();
        final int px = Ui.dp(app, sizeDp);
        final String key = uri + "@" + px;
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
                    Bitmap full = ImageUtil.decodeUri(app.getContentResolver(), android.net.Uri.parse(uri), px * 2);
                    if (full != null) {
                        int w = full.getWidth();
                        int h = full.getHeight();
                        int s = Math.min(w, h);
                        Bitmap crop = Bitmap.createBitmap(full, (w - s) / 2, (h - s) / 2, s, s);
                        out = Bitmap.createScaledBitmap(crop, px, px, true);
                        if (crop != full && crop != out) {
                            crop.recycle();
                        }
                        if (full != out) {
                            full.recycle();
                        }
                        CACHE.put(key, out);
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                }
                final Bitmap result = out;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && key.equals(view.getTag())) {
                            view.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
