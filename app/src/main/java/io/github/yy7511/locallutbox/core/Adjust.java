package io.github.yy7511.locallutbox.core;

/** Basic per-image adjustments. All values are normalized to -1..1 (exposure -2..2). */
public final class Adjust {
    public float exposure;
    public float contrast;
    public float highlights;
    public float shadows;
    public float saturation;
    public float vibrance;
    public float temperature;
    public float tint;
    public float clarity;

    public Adjust copy() {
        Adjust a = new Adjust();
        a.exposure = exposure;
        a.contrast = contrast;
        a.highlights = highlights;
        a.shadows = shadows;
        a.saturation = saturation;
        a.vibrance = vibrance;
        a.temperature = temperature;
        a.tint = tint;
        a.clarity = clarity;
        return a;
    }

    public void set(Adjust o) {
        exposure = o.exposure;
        contrast = o.contrast;
        highlights = o.highlights;
        shadows = o.shadows;
        saturation = o.saturation;
        vibrance = o.vibrance;
        temperature = o.temperature;
        tint = o.tint;
        clarity = o.clarity;
    }

    public void reset() {
        exposure = contrast = highlights = shadows = saturation = 0f;
        vibrance = temperature = tint = clarity = 0f;
    }

    public boolean isNeutral() {
        return Math.abs(exposure) < 1e-4f && Math.abs(contrast) < 1e-4f
                && Math.abs(highlights) < 1e-4f && Math.abs(shadows) < 1e-4f
                && Math.abs(saturation) < 1e-4f && Math.abs(vibrance) < 1e-4f
                && Math.abs(temperature) < 1e-4f && Math.abs(tint) < 1e-4f
                && Math.abs(clarity) < 1e-4f;
    }

    public String encode() {
        return exposure + ";" + contrast + ";" + highlights + ";" + shadows + ";"
                + saturation + ";" + vibrance + ";" + temperature + ";" + tint + ";" + clarity;
    }

    public static Adjust decode(String s) {
        Adjust a = new Adjust();
        if (s == null || s.length() == 0) {
            return a;
        }
        String[] parts = s.split(";");
        float[] v = new float[9];
        for (int i = 0; i < 9 && i < parts.length; i++) {
            try {
                v[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
                v[i] = 0f;
            }
        }
        a.exposure = v[0];
        a.contrast = v[1];
        a.highlights = v[2];
        a.shadows = v[3];
        a.saturation = v[4];
        a.vibrance = v[5];
        a.temperature = v[6];
        a.tint = v[7];
        a.clarity = v[8];
        return a;
    }
}
