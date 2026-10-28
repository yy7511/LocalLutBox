package io.github.yy7511.locallutbox.core;

/**
 * Pure-Java image pipeline (operates on ARGB int arrays, no Android dependencies)
 * so it can be unit-tested on a desktop JVM.
 */
public final class Pipeline {

    private Pipeline() {
    }

    /** Pre-computed per-pixel constants for a given adjustment + LUT. */
    public static final class ToneParams {
        public final float rMul;
        public final float gMul;
        public final float bMul;
        public final float contrastK;
        public final float highlights;
        public final float shadows;
        public final float satMul;
        public final float vibrance;
        public final Lut lut;
        private final float[] tmp = new float[3];

        public ToneParams(Adjust a, Lut lut) {
            float t = a.temperature;
            float ti = a.tint;
            float rMul = 1f + t * 0.25f;
            float bMul = 1f - t * 0.25f;
            float gMul = 1f - ti * 0.16f;
            rMul *= 1f + ti * 0.06f;
            bMul *= 1f + ti * 0.06f;
            float e = (float) Math.pow(2.0, a.exposure);
            this.rMul = rMul * e;
            this.gMul = gMul * e;
            this.bMul = bMul * e;
            this.contrastK = 1f + a.contrast * 0.75f;
            this.highlights = a.highlights;
            this.shadows = a.shadows;
            this.satMul = 1f + a.saturation;
            this.vibrance = a.vibrance;
            this.lut = lut;
        }

        public void apply(float r, float g, float b, float[] out) {
            r *= rMul;
            g *= gMul;
            b *= bMul;

            if (contrastK != 1f) {
                r = (r - 0.5f) * contrastK + 0.5f;
                g = (g - 0.5f) * contrastK + 0.5f;
                b = (b - 0.5f) * contrastK + 0.5f;
            }

            if (highlights != 0f || shadows != 0f) {
                float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                if (highlights != 0f) {
                    float m = (l - 0.5f) * 2f;
                    if (m > 1f) {
                        m = 1f;
                    }
                    if (m > 0f) {
                        float d = highlights * m * m * 0.35f;
                        r += d;
                        g += d;
                        b += d;
                    }
                }
                if (shadows != 0f) {
                    float m = (0.5f - l) * 2f;
                    if (m > 1f) {
                        m = 1f;
                    }
                    if (m > 0f) {
                        float d = shadows * m * m * 0.35f;
                        r += d;
                        g += d;
                        b += d;
                    }
                }
            }

            if (satMul != 1f || vibrance != 0f) {
                float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float sat = satMul;
                if (vibrance != 0f) {
                    float mx = Math.max(r, Math.max(g, b));
                    float mn = Math.min(r, Math.min(g, b));
                    sat += vibrance * (1f - (mx - mn));
                }
                r = l + (r - l) * sat;
                g = l + (g - l) * sat;
                b = l + (b - l) * sat;
            }

            if (lut != null) {
                lut.sample(out, r, g, b);
                out[0] = Lut.clamp01(out[0]);
                out[1] = Lut.clamp01(out[1]);
                out[2] = Lut.clamp01(out[2]);
            } else {
                out[0] = Lut.clamp01(r);
                out[1] = Lut.clamp01(g);
                out[2] = Lut.clamp01(b);
            }
        }
    }

    /** Applies adjustments + LUT (and optional clarity) in place. */
    public static void apply(int[] px, int w, int h, Adjust adj, Lut lut) {
        apply(px, w, h, adj, lut, ColorSpace.SRGB);
    }

    /**
     * @param srcSpace colour space of the incoming pixels; converted into the LUT's
     *                 target space before grading, then back to sRGB for output.
     */
    public static void apply(int[] px, int w, int h, Adjust adj, Lut lut, int srcSpace) {
        if (px == null || w <= 0 || h <= 0) {
            return;
        }
        int dstSpace = lut == null ? ColorSpace.SRGB : ColorSpace.clampSpace(lut.space);
        boolean sameSpace = ColorSpace.clampSpace(srcSpace) == dstSpace;
        if (sameSpace && adj.isNeutral() && lut == null) {
            return;
        }
        if (!sameSpace) {
            ColorSpace.convert(px, srcSpace, dstSpace);
        }
        ToneParams tp = new ToneParams(adj, lut);
        int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors()));
        if (h < 64 && threads > 1) {
            threads = 1;
        }
        if (threads == 1) {
            applyRows(px, w, 0, h, tp);
        } else {
            Thread[] pool = new Thread[threads];
            int rowsPer = (h + threads - 1) / threads;
            for (int i = 0; i < threads; i++) {
                final int y0 = i * rowsPer;
                final int y1 = Math.min(h, y0 + rowsPer);
                if (y0 >= y1) {
                    continue;
                }
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        applyRows(px, w, y0, y1, tp);
                    }
                }, "lut-row-" + i);
                pool[i] = t;
                t.start();
            }
            for (int i = 0; i < threads; i++) {
                if (pool[i] != null) {
                    try {
                        pool[i].join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (dstSpace != ColorSpace.SRGB) {
            ColorSpace.convert(px, dstSpace, ColorSpace.SRGB);
        }
        if (Math.abs(adj.clarity) > 0.01f) {
            applyClarity(px, w, h, adj.clarity);
        }
    }

    public static void applyRows(int[] px, int w, int y0, int y1, ToneParams tp) {
        float[] out = new float[3];
        int end = y1 * w;
        for (int i = y0 * w; i < end; i++) {
            int c = px[i];
            float r = ((c >> 16) & 0xFF) * (1f / 255f);
            float g = ((c >> 8) & 0xFF) * (1f / 255f);
            float b = (c & 0xFF) * (1f / 255f);
            tp.apply(r, g, b, out);
            int rr = (int) (out[0] * 255f + 0.5f);
            int gg = (int) (out[1] * 255f + 0.5f);
            int bb = (int) (out[2] * 255f + 0.5f);
            if (rr > 255) {
                rr = 255;
            }
            if (gg > 255) {
                gg = 255;
            }
            if (bb > 255) {
                bb = 255;
            }
            px[i] = (c & 0xFF000000) | (rr << 16) | (gg << 8) | bb;
        }
    }

    /** Local contrast (unsharp on luma) with a separable box blur. */
    static void applyClarity(int[] px, int w, int h, float amount) {
        byte[] luma = new byte[w * h];
        for (int i = 0; i < luma.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int l = (int) (0.2126f * r + 0.7152f * g + 0.0722f * b);
            luma[i] = (byte) (l < 0 ? 0 : (l > 255 ? 255 : l));
        }
        int radius = Math.max(1, Math.round(1.5f + Math.abs(amount) * 3.5f));
        byte[] blurred = new byte[w * h];
        blurHorizontal(luma, blurred, w, h, radius);
        blurVertical(blurred, luma, w, h, radius);

        float amt = amount * 1.15f;
        for (int i = 0; i < px.length; i++) {
            int orig = px[i];
            int detail = (blurred[i] & 0xFF) - (luma[i] & 0xFF);
            int d = Math.round(detail * amt);
            if (d == 0) {
                continue;
            }
            int r = ((orig >> 16) & 0xFF) + d;
            int g = ((orig >> 8) & 0xFF) + d;
            int b = (orig & 0xFF) + d;
            if (r < 0) {
                r = 0;
            }
            if (r > 255) {
                r = 255;
            }
            if (g < 0) {
                g = 0;
            }
            if (g > 255) {
                g = 255;
            }
            if (b < 0) {
                b = 0;
            }
            if (b > 255) {
                b = 255;
            }
            px[i] = (orig & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    private static void blurHorizontal(byte[] src, byte[] dst, int w, int h, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sum = 0;
            for (int k = -radius; k <= radius; k++) {
                int x = k < 0 ? 0 : (k >= w ? w - 1 : k);
                sum += src[row + x] & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (byte) (sum / window);
                int addX = x + radius + 1;
                if (addX > w - 1) {
                    addX = w - 1;
                }
                int subX = x - radius;
                if (subX < 0) {
                    subX = 0;
                }
                sum += (src[row + addX] & 0xFF) - (src[row + subX] & 0xFF);
            }
        }
    }

    private static void blurVertical(byte[] src, byte[] dst, int w, int h, int radius) {
        int window = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int k = -radius; k <= radius; k++) {
                int y = k < 0 ? 0 : (k >= h ? h - 1 : k);
                sum += src[y * w + x] & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (byte) (sum / window);
                int addY = y + radius + 1;
                if (addY > h - 1) {
                    addY = h - 1;
                }
                int subY = y - radius;
                if (subY < 0) {
                    subY = 0;
                }
                sum += (src[addY * w + x] & 0xFF) - (src[subY * w + x] & 0xFF);
            }
        }
    }
}
