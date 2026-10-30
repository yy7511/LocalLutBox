package io.github.yy7511.locallutbox.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LUT import, storage, loading and preview-thumbnail cache. */
public final class LutManager {

    private static String cachedId;
    private static Lut cachedLut;

    private LutManager() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "luts");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File thumbDir(Context c) {
        File d = new File(c.getCacheDir(), "lutthumbs");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static ArrayList<Store.LutEntry> list(Context c) {
        return Store.luts(c);
    }

    public static Store.LutEntry importUri(Context c, Uri uri) throws Exception {
        String display = ImageUtil.getDisplayName(c.getContentResolver(), uri);
        String ext = ImageUtil.extension(display);
        String text = ImageUtil.readText(c.getContentResolver(), uri, 24 * 1024 * 1024);

        Lut lut;
        String label;
        String params = "";
        if ("cube".equals(ext)) {
            lut = CubeParser.parse(text, display);
            label = labelOf(lut);
            params = cubeParams(text, lut);
        } else if ("xmp".equals(ext)) {
            lut = XmpParser.bake(text, display);
            label = "XMP · " + lut.size + "³";
            params = XmpParser.describe(text);
        } else if ("3dl".equals(ext)) {
            lut = CubeParser.parse3dl(text, display);
            label = "3DL · " + lut.size + "³";
            params = "3DL 蓝快序重映射 · 网格 " + lut.size + "³";
        } else {
            try {
                lut = CubeParser.parse(text, display);
                label = labelOf(lut);
                params = cubeParams(text, lut);
            } catch (Exception e) {
                lut = XmpParser.bake(text, display);
                label = "XMP · " + lut.size + "³";
                params = XmpParser.describe(text);
            }
        }

        ArrayList<Store.LutEntry> entries = Store.luts(c);
        String base = ImageUtil.baseName(display);
        if (base == null || base.length() == 0) {
            base = "LUT";
        }
        String name = base;
        int n = 2;
        while (containsName(entries, name)) {
            name = base + " (" + n + ")";
            n++;
        }

        String id = "l" + System.currentTimeMillis() + "_" + (int) (Math.random() * 900 + 100);
        File file = new File(dir(c), id + ".lut");
        LutCodec.write(lut, file);

        Store.LutEntry entry = new Store.LutEntry();
        entry.id = id;
        entry.name = name;
        entry.file = file.getAbsolutePath();
        entry.kind = lut.kind;
        entry.size = lut.size;
        entry.label = label;
        entry.params = params;
        entries.add(entry);
        Store.saveLuts(c, entries);
        return entry;
    }

    private static String labelOf(Lut lut) {
        if (lut.kind == Lut.KIND_1D) {
            return String.format(Locale.US, "1D · %d", lut.size);
        }
        return String.format(Locale.US, "3D · %d³", lut.size);
    }

    /** Grid size, input domain and the optional TITLE line of a .cube file. */
    private static String cubeParams(String text, Lut lut) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("^\\s*TITLE\\s+\"?([^\"\\r\\n]+)\"?",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            sb.append("标题 ").append(m.group(1).trim()).append(" · ");
        }
        if (lut.kind == Lut.KIND_1D) {
            sb.append("1D 网格 ").append(lut.size);
        } else {
            sb.append("3D 网格 ").append(lut.size).append("³");
        }
        sb.append(String.format(Locale.US, " · 输入域 %.3f–%.3f", lut.domainMin, lut.domainMax));
        return sb.toString();
    }

    private static boolean containsName(ArrayList<Store.LutEntry> list, String name) {
        for (Store.LutEntry e : list) {
            if (name.equals(e.name)) {
                return true;
            }
        }
        return false;
    }

    public static Store.LutEntry find(Context c, String id) {
        if (id == null || id.length() == 0) {
            return null;
        }
        for (Store.LutEntry e : Store.luts(c)) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    public static synchronized Lut load(Context c, String id) {
        if (id == null || id.length() == 0) {
            return null;
        }
        if (id.equals(cachedId) && cachedLut != null) {
            return cachedLut;
        }
        Store.LutEntry e = find(c, id);
        if (e == null) {
            return null;
        }
        try {
            Lut lut = LutCodec.read(c, new File(e.file));
            lut.space = ColorSpace.clampSpace(e.space);
            cachedId = id;
            cachedLut = lut;
            return lut;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Loads a stack in order, skipping ids that no longer exist. */
    public static Lut[] loadAll(Context c, java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new Lut[0];
        }
        ArrayList<Lut> out = new ArrayList<Lut>();
        for (String id : ids) {
            Lut lut = load(c, id);
            if (lut != null) {
                out.add(lut);
            }
        }
        return out.toArray(new Lut[out.size()]);
    }

    /** Updates the colour space a LUT is authored for and drops its cached thumbnail. */
    public static void setSpace(Context c, String id, int space) {
        ArrayList<Store.LutEntry> entries = Store.luts(c);
        int old = ColorSpace.SRGB;
        for (Store.LutEntry e : entries) {
            if (id.equals(e.id)) {
                old = e.space;
                e.space = ColorSpace.clampSpace(space);
                break;
            }
        }
        Store.saveLuts(c, entries);
        deleteThumbs(c, id);
        if (old != space) {
            invalidateThumbs(c);
        }
        synchronized (LutManager.class) {
            cachedId = null;
            cachedLut = null;
        }
    }

    public static synchronized Bitmap thumb(Context c, String id) {
        if (id == null || id.length() == 0) {
            return null;
        }
        Store.LutEntry entry = find(c, id);
        if (entry == null) {
            return null;
        }
        File f = new File(thumbDir(c), id + "_" + Preview.version(c) + "_s" + entry.space + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                return b;
            }
        }
        Lut lut = load(c, id);
        if (lut == null) {
            return null;
        }
        Bitmap src = Preview.square(c, 240);
        Bitmap out;
        try {
            out = Renderer.render(src, lut, new Adjust(), 240);
        } finally {
            src.recycle();
        }
        if (out == null) {
            return null;
        }
        try {
            FileOutputStream fos = new FileOutputStream(f);
            try {
                out.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void delete(Context c, String id) {
        ArrayList<Store.LutEntry> entries = Store.luts(c);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                File f = new File(entries.get(i).file);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                entries.remove(i);
                Store.saveLuts(c, entries);
                break;
            }
        }
        deleteThumbs(c, id);
        synchronized (LutManager.class) {
            if (id.equals(cachedId)) {
                cachedId = null;
                cachedLut = null;
            }
        }
    }

    private static void deleteThumbs(Context c, String id) {
        File[] files = thumbDir(c).listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(id + "_")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    public static void invalidateThumbs(Context c) {
        File d = thumbDir(c);
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
