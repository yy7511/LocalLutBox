package io.github.yy7511.locallutbox.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parser for .cube (1D/3D) and simple .3dl LUT files. */
public final class CubeParser {

    public static Lut parse(String text, String name) {
        if (text == null) {
            throw new IllegalArgumentException("LUT 文件为空");
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        int size3 = -1;
        int size1 = -1;
        float dmin = 0f;
        float dmax = 1f;
        String title = null;
        List<Float> values = new ArrayList<Float>();

        String[] lines = text.split("\r\n|\r|\n");
        for (int ln = 0; ln < lines.length; ln++) {
            String raw = lines[ln];
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.length() == 0) {
                continue;
            }
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("TITLE")) {
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (q1 >= 0 && q2 > q1) {
                    title = line.substring(q1 + 1, q2);
                }
            } else if (upper.startsWith("LUT_3D_SIZE")) {
                size3 = Integer.parseInt(token(line, 1));
            } else if (upper.startsWith("LUT_1D_SIZE")) {
                size1 = Integer.parseInt(token(line, 1));
            } else if (upper.startsWith("DOMAIN_MIN")) {
                String[] t = tokens(line);
                dmin = Float.parseFloat(t[1]);
                if (t.length > 2) {
                    dmin = Math.min(dmin, Float.parseFloat(t[2]));
                }
                if (t.length > 3) {
                    dmin = Math.min(dmin, Float.parseFloat(t[3]));
                }
            } else if (upper.startsWith("DOMAIN_MAX")) {
                String[] t = tokens(line);
                dmax = Float.parseFloat(t[1]);
                if (t.length > 2) {
                    dmax = Math.max(dmax, Float.parseFloat(t[2]));
                }
                if (t.length > 3) {
                    dmax = Math.max(dmax, Float.parseFloat(t[3]));
                }
            } else if (upper.startsWith("LUT_3D_INPUT_RANGE") || upper.startsWith("LUT_1D_INPUT_RANGE")) {
                // Ignore non-standard input range hints.
            } else {
                char c0 = line.charAt(0);
                if ((c0 >= '0' && c0 <= '9') || c0 == '-' || c0 == '+' || c0 == '.') {
                    String[] t = tokens(line);
                    for (int i = 0; i < t.length; i++) {
                        try {
                            values.add(Float.parseFloat(t[i]));
                        } catch (NumberFormatException ignored) {
                            // skip garbage
                        }
                    }
                }
            }
        }

        float[] data = new float[values.size()];
        for (int i = 0; i < data.length; i++) {
            data[i] = values.get(i);
        }

        Lut lut = null;
        if (size3 > 1) {
            int expect = size3 * size3 * size3 * 3;
            if (data.length < expect) {
                throw new IllegalArgumentException("3D LUT 数据不完整（需要 " + expect + " 个数值，实际 " + data.length + "）");
            }
            if (data.length > expect) {
                data = trim(data, expect);
            }
            lut = Lut.from3D(data, size3, title != null ? title : name);
            lut.domainMin = dmin;
            lut.domainMax = dmax;
        } else if (size1 > 1) {
            int expect = size1 * 3;
            if (data.length < expect) {
                throw new IllegalArgumentException("1D LUT 数据不完整（需要 " + expect + " 个数值，实际 " + data.length + "）");
            }
            if (data.length > expect) {
                data = trim(data, expect);
            }
            Lut l = new Lut();
            l.kind = Lut.KIND_1D;
            l.size = size1;
            l.data = data;
            l.name = title != null ? title : name;
            lut = l;
        }
        if (lut == null) {
            throw new IllegalArgumentException("未找到 LUT_3D_SIZE / LUT_1D_SIZE 声明");
        }
        return lut;
    }

    /** Minimal .3dl support: first line is a mesh list (or a single size), values are 10/12-bit ints. */
    public static Lut parse3dl(String text, String name) {
        if (text == null) {
            throw new IllegalArgumentException("3DL 文件为空");
        }
        String[] lines = text.split("\r\n|\r|\n");
        int size = -1;
        int start = -1;
        int maxVal = 4095;
        List<Float> values = new ArrayList<Float>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            if (start < 0) {
                String[] t = line.split("\\s+");
                if (t.length == 1) {
                    try {
                        int n = Integer.parseInt(t[0]);
                        if (n > 1 && n <= 256) {
                            size = n;
                            start = i + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                boolean ascending = t.length > 1;
                int prev = -1;
                for (int k = 0; k < t.length; k++) {
                    try {
                        int v = Integer.parseInt(t[k]);
                        if (prev >= 0 && v <= prev) {
                            ascending = false;
                            break;
                        }
                        prev = v;
                    } catch (NumberFormatException e) {
                        ascending = false;
                        break;
                    }
                }
                if (ascending) {
                    size = t.length;
                    start = i + 1;
                } else {
                    throw new IllegalArgumentException("无法识别的 3DL 头部");
                }
            } else {
                String[] t = line.split("\\s+");
                for (int k = 0; k < t.length; k++) {
                    try {
                        float v = Float.parseFloat(t[k]);
                        if (v > maxVal) {
                            maxVal = (int) Math.ceil(v);
                        }
                        values.add(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (size < 2) {
            throw new IllegalArgumentException("无法识别的 3DL 网格尺寸");
        }
        int expect = size * size * size * 3;
        if (values.size() < expect) {
            throw new IllegalArgumentException("3DL 数据不完整");
        }
        // 3DL stores blue fastest; convert to our red-fastest layout.
        int sx = size;
        float scale = 1f / maxVal;
        float[] data = new float[expect];
        int src = 0;
        for (int r = 0; r < sx; r++) {
            for (int g = 0; g < sx; g++) {
                for (int b = 0; b < sx; b++) {
                    float vr = values.get(src++) * scale;
                    float vg = values.get(src++) * scale;
                    float vb = values.get(src++) * scale;
                    int dst = ((b * sx + g) * sx + r) * 3;
                    data[dst] = vr;
                    data[dst + 1] = vg;
                    data[dst + 2] = vb;
                }
            }
        }
        Lut lut = Lut.from3D(data, size, name);
        return lut;
    }

    private static float[] trim(float[] src, int n) {
        float[] out = new float[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

    private static String[] tokens(String line) {
        return line.trim().split("\\s+");
    }

    private static String token(String line, int idx) {
        String[] t = tokens(line);
        if (idx >= t.length) {
            throw new IllegalArgumentException("LUT 头部格式错误: " + line);
        }
        return t[idx];
    }

    private CubeParser() {
    }
}
