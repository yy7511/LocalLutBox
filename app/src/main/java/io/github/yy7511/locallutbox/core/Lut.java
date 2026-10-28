package io.github.yy7511.locallutbox.core;

/**
 * 3D / 1D LUT. Data layout for 3D: red index changes fastest,
 * index = ((b * size + g) * size + r) * 3.
 */
public final class Lut {
    public static final int KIND_3D = 0;
    public static final int KIND_1D = 1;
    public static final int DEFAULT_3D_SIZE = 33;

    public String name = "";
    public int kind = KIND_3D;
    public int size = DEFAULT_3D_SIZE;
    public float domainMin = 0f;
    public float domainMax = 1f;
    /** Colour space the LUT was authored for (see {@link ColorSpace}). */
    public int space = ColorSpace.SRGB;
    public float[] data;

    public static Lut from3D(float[] data, int size, String name) {
        Lut lut = new Lut();
        lut.kind = KIND_3D;
        lut.size = size;
        lut.data = data;
        lut.name = name;
        return lut;
    }

    public static Lut identity3D(int size) {
        float[] d = new float[size * size * size * 3];
        int i = 0;
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    d[i++] = r / (float) (size - 1);
                    d[i++] = g / (float) (size - 1);
                    d[i++] = b / (float) (size - 1);
                }
            }
        }
        return from3D(d, size, "identity");
    }

    public static Lut identity1D(int size) {
        float[] d = new float[size * 3];
        for (int i = 0; i < size; i++) {
            float v = i / (float) (size - 1);
            d[i * 3] = v;
            d[i * 3 + 1] = v;
            d[i * 3 + 2] = v;
        }
        Lut lut = new Lut();
        lut.kind = KIND_1D;
        lut.size = size;
        lut.data = d;
        return lut;
    }

    /** Samples the LUT; writes r,g,b into out[0..2]. Input is clamped to the LUT domain. */
    public void sample(float[] out, float r, float g, float b) {
        if (kind == KIND_1D) {
            sample1D(out, r, g, b);
        } else {
            sample3D(out, r, g, b);
        }
    }

    private void sample1D(float[] out, float r, float g, float b) {
        int ir = index1D(r);
        int ig = index1D(g);
        int ib = index1D(b);
        out[0] = clamp01(data[ir * 3]);
        out[1] = clamp01(data[ig * 3 + 1]);
        out[2] = clamp01(data[ib * 3 + 2]);
    }

    private int index1D(float v) {
        int i = Math.round(clamp01(v) * (size - 1));
        if (i < 0) {
            i = 0;
        }
        if (i >= size) {
            i = size - 1;
        }
        return i;
    }

    private void sample3D(float[] out, float r, float g, float b) {
        float span = domainMax - domainMin;
        if (span == 0f) {
            span = 1f;
        }
        float nr = (clamp01((r - domainMin) / span)) * (size - 1);
        float ng = (clamp01((g - domainMin) / span)) * (size - 1);
        float nb = (clamp01((b - domainMin) / span)) * (size - 1);

        int r0 = (int) nr;
        int g0 = (int) ng;
        int b0 = (int) nb;
        if (r0 >= size - 1) {
            r0 = size - 2;
        }
        if (g0 >= size - 1) {
            g0 = size - 2;
        }
        if (b0 >= size - 1) {
            b0 = size - 2;
        }
        if (r0 < 0) {
            r0 = 0;
        }
        if (g0 < 0) {
            g0 = 0;
        }
        if (b0 < 0) {
            b0 = 0;
        }
        int r1 = r0 + 1;
        int g1 = g0 + 1;
        int b1 = b0 + 1;
        float fr = nr - r0;
        float fg = ng - g0;
        float fb = nb - b0;

        int strideR = 3;
        int strideG = size * 3;
        int strideB = size * size * 3;
        int i000 = b0 * strideB + g0 * strideG + r0 * strideR;
        int i100 = i000 + strideR;
        int i010 = i000 + strideG;
        int i110 = i010 + strideR;
        int i001 = i000 + strideB;
        int i101 = i001 + strideR;
        int i011 = i001 + strideG;
        int i111 = i011 + strideR;

        float[] d = data;
        float c000 = d[i000];
        float c100 = d[i100];
        float c010 = d[i010];
        float c110 = d[i110];
        float c001 = d[i001];
        float c101 = d[i101];
        float c011 = d[i011];
        float c111 = d[i111];

        float c00 = c000 + (c100 - c000) * fr;
        float c10 = c010 + (c110 - c010) * fr;
        float c01 = c001 + (c101 - c001) * fr;
        float c11 = c011 + (c111 - c011) * fr;
        float c0 = c00 + (c10 - c00) * fg;
        float c1 = c01 + (c11 - c01) * fg;
        out[0] = clamp01(c0 + (c1 - c0) * fb);

        i000++;
        i100++;
        i010++;
        i110++;
        i001++;
        i101++;
        i011++;
        i111++;

        c000 = d[i000];
        c100 = d[i100];
        c010 = d[i010];
        c110 = d[i110];
        c001 = d[i001];
        c101 = d[i101];
        c011 = d[i011];
        c111 = d[i111];
        c00 = c000 + (c100 - c000) * fr;
        c10 = c010 + (c110 - c010) * fr;
        c01 = c001 + (c101 - c001) * fr;
        c11 = c011 + (c111 - c011) * fr;
        c0 = c00 + (c10 - c00) * fg;
        c1 = c01 + (c11 - c01) * fg;
        out[1] = clamp01(c0 + (c1 - c0) * fb);

        i000++;
        i100++;
        i010++;
        i110++;
        i001++;
        i101++;
        i011++;
        i111++;

        c000 = d[i000];
        c100 = d[i100];
        c010 = d[i010];
        c110 = d[i110];
        c001 = d[i001];
        c101 = d[i101];
        c011 = d[i011];
        c111 = d[i111];
        c00 = c000 + (c100 - c000) * fr;
        c10 = c010 + (c110 - c010) * fr;
        c01 = c001 + (c101 - c001) * fr;
        c11 = c011 + (c111 - c011) * fr;
        c0 = c00 + (c10 - c00) * fg;
        c1 = c01 + (c11 - c01) * fg;
        out[2] = clamp01(c0 + (c1 - c0) * fb);
    }

    public static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        if (i < 0) {
            return 0;
        }
        if (i > 255) {
            return 255;
        }
        return i;
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
