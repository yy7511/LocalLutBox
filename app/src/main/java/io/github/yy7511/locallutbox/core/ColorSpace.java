package io.github.yy7511.locallutbox.core;

import java.util.HashMap;

/**
 * Colour-space maths for RGB sources: transfer-curve decode/encode plus a 3x3
 * matrix through XYZ (D65). Matrices are whitepoint-normalised, and ProPhoto's
 * D50 white is Bradford-adapted to D65 so neutral greys stay neutral.
 */
public final class ColorSpace {

    public static final int SRGB = 0;
    public static final int ADOBE_RGB = 1;
    public static final int DISPLAY_P3 = 2;
    public static final int PROPHOTO = 3;
    public static final int REC2020 = 4;

    private static final String[] NAMES = {"sRGB", "Adobe RGB", "Display P3", "ProPhoto RGB", "Rec.2020"};

    /** RGB -> XYZ (D65) matrices, rows are X/Y/Z. */
    private static final float[][] TO_XYZ = {
            {0.4124564f, 0.3575761f, 0.1804375f, 0.2126729f, 0.7151522f, 0.0721750f,
                    0.0193339f, 0.1191920f, 0.9503041f},
            {0.5767309f, 0.1855540f, 0.1881852f, 0.2973769f, 0.6273491f, 0.0752741f,
                    0.0270343f, 0.0706872f, 0.9911085f},
            {0.4866327f, 0.2656632f, 0.1981742f, 0.2290036f, 0.6917267f, 0.0792697f,
                    -0.0000000f, 0.0451126f, 1.0437174f},
            {0.7555949f, 0.1127855f, 0.0820510f, 0.2683350f, 0.7151300f, 0.0165291f,
                    0.0039100f, -0.0129188f, 1.0974263f},
            {0.6370102f, 0.1446150f, 0.1688448f, 0.2627217f, 0.6779893f, 0.0592890f,
                    0.0000000f, 0.0280723f, 1.0607577f},
    };

    private static final int ENCODE_SIZE = 4096;
    private static final HashMap<Integer, float[]> MATRIX_CACHE = new HashMap<Integer, float[]>();
    private static final float[][] DECODE_TABLE = new float[5][256];
    private static final float[][] ENCODE_TABLE = new float[5][ENCODE_SIZE];

    static {
        for (int s = 0; s < 5; s++) {
            for (int i = 0; i < 256; i++) {
                DECODE_TABLE[s][i] = decode(s, i / 255f);
            }
            for (int i = 0; i < ENCODE_SIZE; i++) {
                ENCODE_TABLE[s][i] = encode(s, i / (float) (ENCODE_SIZE - 1));
            }
        }
    }

    private ColorSpace() {
    }

    public static String name(int space) {
        return NAMES[clampSpace(space)];
    }

    public static int clampSpace(int space) {
        return space < 0 || space >= NAMES.length ? SRGB : space;
    }

    /** Converts ARGB pixels in place from one RGB space to another (alpha untouched). */
    public static void convert(int[] px, int from, int to) {
        from = clampSpace(from);
        to = clampSpace(to);
        if (px == null || from == to) {
            return;
        }
        float[] m = matrix(from, to);
        float[] dec = DECODE_TABLE[from];
        float[] enc = ENCODE_TABLE[to];
        final int last = ENCODE_SIZE - 1;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float r = dec[(c >> 16) & 0xFF];
            float g = dec[(c >> 8) & 0xFF];
            float b = dec[c & 0xFF];
            float lr = m[0] * r + m[1] * g + m[2] * b;
            float lg = m[3] * r + m[4] * g + m[5] * b;
            float lb = m[6] * r + m[7] * g + m[8] * b;
            int rr = (int) (enc[tableIndex(lr, last)] * 255f + 0.5f);
            int gg = (int) (enc[tableIndex(lg, last)] * 255f + 0.5f);
            int bb = (int) (enc[tableIndex(lb, last)] * 255f + 0.5f);
            px[i] = (c & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
        }
    }

    private static int tableIndex(float linear, int last) {
        if (linear <= 0f) {
            return 0;
        }
        if (linear >= 1f) {
            return last;
        }
        return (int) (linear * last + 0.5f);
    }

    private static synchronized float[] matrix(int from, int to) {
        int key = from * 8 + to;
        float[] cached = MATRIX_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        float[] invTo = invert(TO_XYZ[to]);
        float[] src = TO_XYZ[from];
        float[] m = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[row * 3 + col] = invTo[row * 3] * src[col]
                        + invTo[row * 3 + 1] * src[3 + col]
                        + invTo[row * 3 + 2] * src[6 + col];
            }
        }
        MATRIX_CACHE.put(key, m);
        return m;
    }

    private static float[] invert(float[] m) {
        float a = m[0], b = m[1], c = m[2];
        float d = m[3], e = m[4], f = m[5];
        float g = m[6], h = m[7], i = m[8];
        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-9f) {
            return new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        }
        float inv = 1f / det;
        return new float[]{
                (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
                (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
                (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv,
        };
    }

    private static float decode(int space, float v) {
        switch (space) {
            case ADOBE_RGB:
                return (float) Math.pow(v, 2.19921875);
            case PROPHOTO:
                return v <= 16f / 512f ? v / 16f : (float) Math.pow(v, 1.8);
            case REC2020:
                return v < 0.08145f ? v / 4.5f : (float) Math.pow((v + 0.0993f) / 1.0993f, 1.0 / 0.45);
            default:
                return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
        }
    }

    private static float encode(int space, float v) {
        switch (space) {
            case ADOBE_RGB:
                return (float) Math.pow(v, 1.0 / 2.19921875);
            case PROPHOTO:
                return v <= 1f / 512f ? v * 16f : (float) Math.pow(v, 1.0 / 1.8);
            case REC2020:
                return v < 0.0181f ? v * 4.5f : 1.0993f * (float) Math.pow(v, 0.45) - 0.0993f;
            default:
                return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1.0 / 2.4) - 0.055f;
        }
    }
}
