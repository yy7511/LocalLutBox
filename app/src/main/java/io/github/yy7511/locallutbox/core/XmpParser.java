package io.github.yy7511.locallutbox.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Approximates Adobe Camera Raw .xmp presets and bakes them into a 33^3 3D LUT.
 * Supports Exposure/Contrast/Highlights/Shadows/Saturation/Vibrance/Temperature/Tint/Clarity
 * plus the main ToneCurvePV2012 curve.
 */
public final class XmpParser {

    private static final Pattern LI = Pattern.compile("<rdf:li[^>]*>([^<]+)</rdf:li>");
    private static final Pattern MAIN_CURVE =
            Pattern.compile("<crs:ToneCurvePV2012(?:\\s[^>]*)?>(.*?)</crs:ToneCurvePV2012>", Pattern.DOTALL);

    private XmpParser() {
    }

    public static Lut bake(String xml, String name) {
        if (xml == null || xml.length() == 0) {
            throw new IllegalArgumentException("XMP 文件为空");
        }
        Adjust a = new Adjust();
        boolean recognized = false;

        Float ev = value(xml, "crs:Exposure2012", "crs:Exposure");
        if (ev != null) {
            a.exposure = clamp(ev, -3f, 3f);
            recognized = true;
        }
        Float contrast = value(xml, "crs:Contrast2012", "crs:Contrast");
        if (contrast != null) {
            a.contrast = clamp(contrast / 100f, -1f, 1f);
            recognized = true;
        }
        Float highlights = value(xml, "crs:Highlights2012", "crs:Highlights");
        if (highlights != null) {
            a.highlights = clamp(highlights / 100f, -1f, 1f);
            recognized = true;
        }
        Float shadows = value(xml, "crs:Shadows2012", "crs:Shadows");
        if (shadows != null) {
            a.shadows = clamp(shadows / 100f, -1f, 1f);
            recognized = true;
        }
        Float sat = value(xml, "crs:Saturation");
        if (sat != null) {
            a.saturation = clamp(sat / 100f, -1f, 1f);
            recognized = true;
        }
        Float vib = value(xml, "crs:Vibrance");
        if (vib != null) {
            a.vibrance = clamp(vib / 100f, -1f, 1f);
            recognized = true;
        }
        Float clarity = value(xml, "crs:Clarity2012", "crs:Clarity");
        if (clarity != null) {
            // Clarity is baked as a mild contrast tweak; local sharpening is not representable in a LUT.
            a.contrast = clamp(a.contrast + clarity / 400f, -1f, 1f);
            recognized = true;
        }
        Float kelvin = value(xml, "crs:Temperature");
        if (kelvin != null && kelvin >= 1000f) {
            a.temperature = clamp((kelvin - 5500f) / 5500f * 0.9f, -0.55f, 0.55f);
            recognized = true;
        }
        Float tint = value(xml, "crs:Tint");
        if (tint != null) {
            a.tint = clamp(tint / 150f * 0.6f, -1f, 1f);
            recognized = true;
        }

        float[] curve = parseCurve(xml);
        if (curve != null) {
            recognized = true;
        }
        if (!recognized && !xml.contains("crs:")) {
            throw new IllegalArgumentException("不是 Camera Raw XMP 预设文件");
        }
        if (!recognized) {
            // No numeric parameters: only a Look name, nothing usable.
            throw new IllegalArgumentException("该 XMP 未包含可烘焙的调色参数（尝试导出为 .cube 3D LUT）");
        }

        int n = Lut.DEFAULT_3D_SIZE;
        float[] data = new float[n * n * n * 3];
        Pipeline.ToneParams tp = new Pipeline.ToneParams(a, null);
        float[] out = new float[3];
        int idx = 0;
        for (int bi = 0; bi < n; bi++) {
            float b = bi / (float) (n - 1);
            for (int gi = 0; gi < n; gi++) {
                float g = gi / (float) (n - 1);
                for (int ri = 0; ri < n; ri++) {
                    float r = ri / (float) (n - 1);
                    tp.apply(r, g, b, out);
                    float rr = out[0];
                    float gg = out[1];
                    float bb = out[2];
                    if (curve != null) {
                        rr = evalCurve(curve, rr);
                        gg = evalCurve(curve, gg);
                        bb = evalCurve(curve, bb);
                    }
                    data[idx++] = Lut.clamp01(rr);
                    data[idx++] = Lut.clamp01(gg);
                    data[idx++] = Lut.clamp01(bb);
                }
            }
        }
        Lut lut = Lut.from3D(data, n, name);
        return lut;
    }

    /** Returns normalized (x,y) pairs flattened: [x0..xn, y0..yn]; null if absent. */
    private static float[] parseCurve(String xml) {
        Matcher m = MAIN_CURVE.matcher(xml);
        if (!m.find()) {
            return null;
        }
        String block = m.group(1);
        Matcher li = LI.matcher(block);
        List<Float> xs = new ArrayList<Float>();
        List<Float> ys = new ArrayList<Float>();
        while (li.find()) {
            String pair = li.group(1).trim();
            String[] parts = pair.split(",");
            if (parts.length < 2) {
                continue;
            }
            try {
                float x = Float.parseFloat(parts[0].trim()) / 255f;
                float y = Float.parseFloat(parts[1].trim()) / 255f;
                if (xs.size() > 0 && x <= xs.get(xs.size() - 1)) {
                    continue;
                }
                xs.add(clamp(x, 0f, 1f));
                ys.add(clamp(y, 0f, 1f));
            } catch (NumberFormatException ignored) {
            }
        }
        if (xs.size() < 2) {
            return null;
        }
        float[] curve = new float[xs.size() * 2];
        for (int i = 0; i < xs.size(); i++) {
            curve[i] = xs.get(i);
            curve[xs.size() + i] = ys.get(i);
        }
        return curve;
    }

    private static float evalCurve(float[] curve, float v) {
        int n = curve.length / 2;
        v = clamp(v, 0f, 1f);
        if (v <= curve[0]) {
            return curve[n];
        }
        if (v >= curve[n - 1]) {
            return curve[n + n - 1];
        }
        for (int i = 1; i < n; i++) {
            if (v <= curve[i]) {
                float x0 = curve[i - 1];
                float x1 = curve[i];
                float y0 = curve[n + i - 1];
                float y1 = curve[n + i];
                float t = (v - x0) / (x1 - x0);
                return y0 + (y1 - y0) * t;
            }
        }
        return v;
    }

    private static Float value(String xml, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Matcher m = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(xml);
            if (m.find()) {
                String raw = m.group(1).trim();
                // Some presets store "+0.30" or "1/3".
                try {
                    return Float.parseFloat(raw);
                } catch (NumberFormatException e) {
                    try {
                        return Float.parseFloat(raw.replace("+", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static String describe() {
        return String.format(Locale.US, "ACR XMP -> %d^3 LUT", Lut.DEFAULT_3D_SIZE);
    }

    /** Reads the raw ACR sliders so the UI can list what this preset actually contains. */
    public static String describe(String xml) {
        StringBuilder sb = new StringBuilder();
        Float ev = value(xml, "crs:Exposure2012", "crs:Exposure");
        if (ev != null) {
            append(sb, "曝光 " + String.format(Locale.US, "%+.2f", ev) + " EV");
        }
        Float contrast = value(xml, "crs:Contrast2012", "crs:Contrast");
        if (contrast != null) {
            append(sb, "对比度 " + String.format(Locale.US, "%+.0f", contrast));
        }
        Float highlights = value(xml, "crs:Highlights2012", "crs:Highlights");
        if (highlights != null) {
            append(sb, "高光 " + String.format(Locale.US, "%+.0f", highlights));
        }
        Float shadows = value(xml, "crs:Shadows2012", "crs:Shadows");
        if (shadows != null) {
            append(sb, "阴影 " + String.format(Locale.US, "%+.0f", shadows));
        }
        Float sat = value(xml, "crs:Saturation");
        if (sat != null) {
            append(sb, "饱和度 " + String.format(Locale.US, "%+.0f", sat));
        }
        Float vib = value(xml, "crs:Vibrance");
        if (vib != null) {
            append(sb, "自然饱和度 " + String.format(Locale.US, "%+.0f", vib));
        }
        Float clarity = value(xml, "crs:Clarity2012", "crs:Clarity");
        if (clarity != null) {
            append(sb, "清晰度 " + String.format(Locale.US, "%+.0f", clarity));
        }
        Float kelvin = value(xml, "crs:Temperature");
        if (kelvin != null && kelvin >= 1000f) {
            append(sb, "色温 " + Math.round(kelvin) + "K");
        }
        Float tint = value(xml, "crs:Tint");
        if (tint != null) {
            append(sb, "色调 " + String.format(Locale.US, "%+.0f", tint));
        }
        if (parseCurve(xml) != null) {
            append(sb, "含色调曲线（烘焙进 LUT）");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String item) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(item);
    }
}
