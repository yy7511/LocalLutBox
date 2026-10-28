package io.github.yy7511.locallutbox.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Procedural built-in preview image (color chart over a warm landscape) so no assets are needed. */
public final class PreviewFactory {

    private static Bitmap cached;

    private PreviewFactory() {
    }

    public static synchronized Bitmap get() {
        if (cached == null || cached.isRecycled()) {
            cached = create();
        }
        return cached;
    }

    public static Bitmap create() {
        final int w = 1200;
        final int h = 800;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float horizon = h * 0.72f;

        // Sky
        p.setShader(new LinearGradient(0, 0, 0, horizon,
                new int[]{0xFF0C1F2E, 0xFF1E4257, 0xFF8A5F4C, 0xFFE9A45F},
                new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, horizon, p);
        p.setShader(null);

        // Sun
        p.setShader(new RadialGradient(880, 258, 235,
                new int[]{0xE6FFF3CC, 0x44FFD18F, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(880, 258, 235, p);
        p.setShader(null);
        p.setColor(0xFFFFEFC2);
        cv.drawCircle(880, 258, 54, p);

        // Distant ranges
        Path path = new Path();
        path.moveTo(0, horizon - 26);
        path.lineTo(90, horizon - 120);
        path.lineTo(190, horizon - 54);
        path.lineTo(300, horizon - 148);
        path.lineTo(430, horizon - 40);
        path.lineTo(560, horizon - 108);
        path.lineTo(700, horizon - 32);
        path.lineTo(860, horizon - 92);
        path.lineTo(1010, horizon - 30);
        path.lineTo(1120, horizon - 96);
        path.lineTo(w, horizon - 44);
        path.lineTo(w, horizon);
        path.lineTo(0, horizon);
        path.close();
        p.setColor(0xFF26414F);
        cv.drawPath(path, p);

        Path near = new Path();
        near.moveTo(0, horizon);
        near.lineTo(0, horizon - 40);
        near.lineTo(150, horizon - 92);
        near.lineTo(330, horizon - 30);
        near.lineTo(520, horizon - 74);
        near.lineTo(730, horizon - 22);
        near.lineTo(960, horizon - 66);
        near.lineTo(w, horizon - 18);
        near.lineTo(w, horizon);
        near.close();
        p.setColor(0xFF17303B);
        cv.drawPath(near, p);

        // Water
        p.setShader(new LinearGradient(0, horizon, 0, h,
                new int[]{0xFF244854, 0xFF122833, 0xFF091218},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, horizon, w, h, p);
        p.setShader(null);
        p.setColor(0x33FFD79B);
        cv.drawOval(new RectF(760, horizon + 26, 1000, horizon + 52), p);
        p.setColor(0x22FFE7C2);
        cv.drawOval(new RectF(820, horizon + 62, 940, horizon + 76), p);

        // Color checker board
        float bx = 54;
        float by = 500;
        float bw = 556;
        float bh = 258;
        p.setColor(0xFFB9B9B9);
        cv.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), 10, 10, p);
        p.setColor(0xFF1A1A1A);
        cv.drawRoundRect(new RectF(bx - 6, by - 6, bx + bw + 6, by + bh + 6), 13, 13, p);

        int[][] patches = {
                {115, 82, 68}, {194, 150, 130}, {157, 122, 87}, {87, 67, 55}, {133, 128, 117}, {103, 114, 117},
                {214, 126, 44}, {80, 91, 146}, {193, 90, 99}, {94, 60, 108}, {157, 188, 64}, {224, 163, 48},
                {56, 61, 150}, {70, 148, 73}, {175, 54, 60}, {231, 119, 53}, {187, 86, 149}, {8, 133, 161},
                {243, 243, 242}, {200, 200, 200}, {160, 160, 160}, {122, 122, 121}, {85, 85, 85}, {52, 52, 52}
        };
        int cellW = 6;
        float cellWf = (bw - 24) / cellW;
        float cellHf = (bh - 24) / 4f;
        for (int i = 0; i < patches.length; i++) {
            int col = i % cellW;
            int row = i / cellW;
            float left = bx + 12 + col * cellWf;
            float top = by + 12 + row * cellHf;
            p.setColor(Color.rgb(patches[i][0], patches[i][1], patches[i][2]));
            cv.drawRoundRect(new RectF(left + 3, top + 3, left + cellWf - 3, top + cellHf - 3), 3, 3, p);
        }

        // Grayscale ramp
        float rx = 690;
        float ry = 690;
        float rw = 440;
        float rh = 46;
        for (int i = 0; i < 32; i++) {
            int v = Math.round(i * 255f / 31f);
            p.setColor(Color.rgb(v, v, v));
            cv.drawRect(rx + i * (rw / 32f), ry, rx + (i + 1) * (rw / 32f), ry + rh, p);
        }

        // Skin tone swatches
        int[][] skins = {{255, 224, 189}, {232, 190, 152}, {198, 150, 112}, {150, 104, 74}};
        for (int i = 0; i < skins.length; i++) {
            p.setColor(Color.rgb(skins[i][0], skins[i][1], skins[i][2]));
            cv.drawCircle(724 + i * 92, 574, 32, p);
            p.setColor(0x33000000);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            cv.drawCircle(724 + i * 92, 574, 32, p);
            p.setStyle(Paint.Style.FILL);
        }

        // Saturated primaries strip (top right, over sky)
        int[] prim = {0xFFE23B3B, 0xFFE2C23B, 0xFF4FD35C, 0xFF3BC6D3, 0xFF3B62E2, 0xFFC23BD3};
        for (int i = 0; i < prim.length; i++) {
            p.setColor(prim[i]);
            cv.drawCircle(760 + i * 58, 434, 22, p);
        }

        return bmp;
    }
}
