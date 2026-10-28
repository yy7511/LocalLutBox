package io.github.yy7511.locallutbox.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local persistence (SharedPreferences + JSON). Everything stays on device. */
public final class Store {

    private static final String PREF = "locallutbox";
    private static final int MAX_LOG_LINES = 80;
    private static final int MAX_PROCESSED = 2000;

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static final class ImageEntry {
        public String uri;
        public String name;
        public String lutId = "";
        public Adjust adj = new Adjust();

        public ImageEntry() {
        }

        public ImageEntry(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    public static final class LutEntry {
        public String id;
        public String name;
        public String file;
        public int kind;
        public int size;
        public String label = "";
        /** Human readable import-time parameters (title, grid, ACR values...). */
        public String params = "";
        /** Target colour space the LUT maps from/to (see {@link ColorSpace}). */
        public int space = ColorSpace.SRGB;
    }

    public static final class AutoCfg {
        public String inTree = "";
        public String outTree = "";
        public String lutId = "";
        public Adjust adj = new Adjust();
        public boolean running;
        /** Detect each photo's ICC profile and convert it into the LUT target space. */
        public boolean convertSpace = true;
        /** Long-edge limit; 0 = keep the original size (no downscaling). */
        public int maxDim = 0;
        /** JPEG quality; 100 = keep the most detail. */
        public int quality = 100;

        public AutoCfg copy() {
            AutoCfg a = new AutoCfg();
            a.inTree = inTree;
            a.outTree = outTree;
            a.lutId = lutId;
            a.adj = adj.copy();
            a.running = running;
            a.convertSpace = convertSpace;
            a.maxDim = maxDim;
            a.quality = quality;
            return a;
        }
    }

    public static ArrayList<ImageEntry> images(Context c) {
        ArrayList<ImageEntry> list = new ArrayList<ImageEntry>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString("images", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ImageEntry e = new ImageEntry();
                e.uri = o.optString("uri", "");
                e.name = o.optString("name", "image");
                e.lutId = o.optString("lut", "");
                e.adj = Adjust.decode(o.optString("adj", ""));
                if (e.uri.length() > 0) {
                    list.add(e);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveImages(Context c, List<ImageEntry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (ImageEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("uri", e.uri);
                o.put("name", e.name);
                o.put("lut", e.lutId == null ? "" : e.lutId);
                o.put("adj", e.adj == null ? "" : e.adj.encode());
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("images", arr.toString()).apply();
    }

    public static ArrayList<LutEntry> luts(Context c) {
        ArrayList<LutEntry> list = new ArrayList<LutEntry>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString("luts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                LutEntry e = new LutEntry();
                e.id = o.optString("id", "");
                e.name = o.optString("name", "LUT");
                e.file = o.optString("file", "");
                e.kind = o.optInt("kind", Lut.KIND_3D);
                e.size = o.optInt("size", 33);
                e.label = o.optString("label", "");
                e.params = o.optString("params", "");
                e.space = ColorSpace.clampSpace(o.optInt("space", ColorSpace.SRGB));
                if (e.id.length() > 0) {
                    list.add(e);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveLuts(Context c, List<LutEntry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (LutEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("name", e.name);
                o.put("file", e.file);
                o.put("kind", e.kind);
                o.put("size", e.size);
                o.put("label", e.label);
                o.put("params", e.params);
                o.put("space", e.space);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("luts", arr.toString()).apply();
    }

    public static AutoCfg auto(Context c) {
        SharedPreferences p = sp(c);
        AutoCfg cfg = new AutoCfg();
        cfg.inTree = p.getString("auto_in", "");
        cfg.outTree = p.getString("auto_out", "");
        cfg.lutId = p.getString("auto_lut", "");
        cfg.adj = Adjust.decode(p.getString("auto_adj", ""));
        cfg.running = p.getBoolean("auto_running", false);
        cfg.convertSpace = p.getBoolean("auto_space", true);
        cfg.maxDim = p.getInt("auto_maxdim", 0);
        cfg.quality = p.getInt("auto_quality", 100);
        return cfg;
    }

    public static void saveAuto(Context c, AutoCfg cfg) {
        sp(c).edit()
                .putString("auto_in", cfg.inTree)
                .putString("auto_out", cfg.outTree)
                .putString("auto_lut", cfg.lutId)
                .putString("auto_adj", cfg.adj.encode())
                .putBoolean("auto_running", cfg.running)
                .putBoolean("auto_space", cfg.convertSpace)
                .putInt("auto_maxdim", cfg.maxDim)
                .putInt("auto_quality", cfg.quality)
                .apply();
    }

    public static boolean autoRunning(Context c) {
        return sp(c).getBoolean("auto_running", false);
    }

    public static void setAutoRunning(Context c, boolean running) {
        sp(c).edit().putBoolean("auto_running", running).apply();
    }

    public static Set<String> processed(Context c) {
        Set<String> set = new LinkedHashSet<String>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString("processed", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static void saveProcessed(Context c, Set<String> keys) {
        JSONArray arr = new JSONArray();
        int skip = Math.max(0, keys.size() - MAX_PROCESSED);
        int i = 0;
        for (String k : keys) {
            if (i++ < skip) {
                continue;
            }
            arr.put(k);
        }
        sp(c).edit().putString("processed", arr.toString()).apply();
    }

    public static void clearProcessed(Context c) {
        sp(c).edit().remove("processed").remove("seed_tree").apply();
    }

    public static String seedTree(Context c) {
        return sp(c).getString("seed_tree", "");
    }

    public static void setSeedTree(Context c, String tree) {
        sp(c).edit().putString("seed_tree", tree == null ? "" : tree).apply();
    }

    public static String logText(Context c) {
        return sp(c).getString("log", "");
    }

    public static void log(Context c, String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String all = ts + "  " + line + "\n" + logText(c);
        String[] lines = all.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LOG_LINES; i++) {
                sb.append(lines[i]).append('\n');
            }
            all = sb.toString();
        }
        sp(c).edit().putString("log", all).apply();
    }

    public static void clearLog(Context c) {
        sp(c).edit().remove("log").apply();
    }

    public static String previewName(Context c) {
        return sp(c).getString("preview_name", "");
    }

    public static void setPreviewName(Context c, String name) {
        sp(c).edit().putString("preview_name", name == null ? "" : name).apply();
    }

    public static String lastLutId(Context c) {
        return sp(c).getString("last_lut", "");
    }

    public static void setLastLutId(Context c, String id) {
        sp(c).edit().putString("last_lut", id == null ? "" : id).apply();
    }

    /** App theme preference: true = dark (default), false = light. */
    public static boolean darkMode(Context c) {
        return sp(c).getBoolean("dark_mode", true);
    }

    public static void setDarkMode(Context c, boolean dark) {
        sp(c).edit().putBoolean("dark_mode", dark).apply();
    }
}
