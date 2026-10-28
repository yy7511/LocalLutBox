package io.github.yy7511.locallutbox.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/** User-selectable preview image (falls back to the built-in color chart). */
public final class Preview {

    private static final String FILE_NAME = "preview_user.jpg";
    private static Bitmap cache;
    private static String cacheKey = "";

    private Preview() {
    }

    public static File userFile(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static boolean hasUser(Context c) {
        File f = userFile(c);
        return f.exists() && f.length() > 1024;
    }

    public static void setFromUri(Context c, Uri uri) throws IOException {
        Bitmap bmp = ImageUtil.decodeUri(c.getContentResolver(), uri, 1600);
        File f = userFile(c);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)) {
                throw new IOException("预览图编码失败");
            }
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        String name = ImageUtil.getDisplayName(c.getContentResolver(), uri);
        Store.setPreviewName(c, name);
        bmp.recycle();
        synchronized (Preview.class) {
            cache = null;
            cacheKey = "";
        }
        LutManager.invalidateThumbs(c);
    }

    public static void reset(Context c) {
        File f = userFile(c);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        Store.setPreviewName(c, "");
        synchronized (Preview.class) {
            cache = null;
            cacheKey = "";
        }
        LutManager.invalidateThumbs(c);
    }

    public static String label(Context c) {
        if (hasUser(c)) {
            String n = Store.previewName(c);
            return n == null || n.length() == 0 ? "自定义图片" : n;
        }
        if (!Store.images(c).isEmpty()) {
            return "导入的图片";
        }
        return "内置色卡";
    }

    public static Bitmap get(Context c) {
        File f = userFile(c);
        if (f.exists() && f.length() > 1024) {
            String key = "user:" + f.lastModified();
            synchronized (Preview.class) {
                if (cache != null && !cache.isRecycled() && key.equals(cacheKey)) {
                    return cache;
                }
                try {
                    Bitmap b = ImageUtil.decodeFile(f, 1600);
                    cache = b;
                    cacheKey = key;
                    return b;
                } catch (Exception ignored) {
                    cache = null;
                }
            }
        }
        return imported(c);
    }

    /** Falls back to the first imported photo, then to the built-in chart. */
    private static Bitmap imported(Context c) {
        ArrayList<Store.ImageEntry> images = Store.images(c);
        if (images.isEmpty()) {
            return PreviewFactory.get();
        }
        String uri = images.get(0).uri;
        String key = "img:" + uri;
        synchronized (Preview.class) {
            if (cache != null && !cache.isRecycled() && key.equals(cacheKey)) {
                return cache;
            }
            try {
                Bitmap b = ImageUtil.decodeUri(c.getContentResolver(), Uri.parse(uri), 1200);
                if (b != null) {
                    cache = b;
                    cacheKey = key;
                    return b;
                }
            } catch (Exception ignored) {
                cache = null;
            }
        }
        return PreviewFactory.get();
    }

    public static int version(Context c) {
        if (hasUser(c)) {
            return (int) (userFile(c).lastModified() / 1000L);
        }
        ArrayList<Store.ImageEntry> images = Store.images(c);
        if (!images.isEmpty()) {
            return images.get(0).uri.hashCode();
        }
        return 1;
    }

    /** Center-cropped square source for LUT thumbnails. Caller must recycle the result. */
    public static Bitmap square(Context c, int size) {
        Bitmap base = get(c);
        int w = base.getWidth();
        int h = base.getHeight();
        int s = Math.min(w, h);
        Bitmap crop = Bitmap.createBitmap(base, (w - s) / 2, (h - s) / 2, s, s);
        Bitmap out = Bitmap.createScaledBitmap(crop, size, size, true);
        if (crop != base && crop != out) {
            crop.recycle();
        }
        return out;
    }
}
