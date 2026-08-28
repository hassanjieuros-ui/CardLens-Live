package com.cardlens.live;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Cheap, allocation-light frame scoring used before OCR/visual matching.
 *
 * The score intentionally looks mostly at the center of the stream where the card usually sits.
 * It estimates sharpness from local luminance differences and applies penalties for severe glare
 * or crushed darkness. This lets CardLens skip obviously motion-blurred frames instead of wasting
 * ML Kit/CPU time on them.
 */
public final class FrameQuality {
    private FrameQuality() {}

    public static Result analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 24 || bitmap.getHeight() < 24) {
            return Result.bad();
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int left = Math.round(width * .10f);
        int right = Math.round(width * .90f);
        int top = Math.round(height * .08f);
        int bottom = Math.round(height * .88f);

        int stepX = Math.max(2, (right - left) / 36);
        int stepY = Math.max(2, (bottom - top) / 54);

        long edgeSum = 0;
        int edgeCount = 0;
        int bright = 0;
        int dark = 0;
        int samples = 0;

        for (int y = top; y + stepY < bottom; y += stepY) {
            for (int x = left; x + stepX < right; x += stepX) {
                int c = luminance(bitmap.getPixel(x, y));
                int r = luminance(bitmap.getPixel(x + stepX, y));
                int d = luminance(bitmap.getPixel(x, y + stepY));

                edgeSum += Math.abs(c - r);
                edgeSum += Math.abs(c - d);
                edgeCount += 2;
                samples++;

                if (c >= 245) bright++;
                if (c <= 18) dark++;
            }
        }

        if (samples == 0 || edgeCount == 0) return Result.bad();

        double normalizedEdge = (edgeSum / (double) edgeCount) / 255.0;
        double glare = bright / (double) samples;
        double darkness = dark / (double) samples;

        // Tuned so a clearly readable stream frame scores high while strong motion blur falls under
        // the OCR threshold. The exposure penalties are deliberately gentle because foil cards can
        // contain bright highlights without actually being unusable.
        double sharpness = clamp((normalizedEdge - .018) / .120);
        double glarePenalty = 1.0 - Math.min(.72, glare * 1.45);
        double darkPenalty = 1.0 - Math.min(.55, darkness * 1.10);
        double quality = clamp(sharpness * glarePenalty * darkPenalty);

        return new Result(quality, sharpness, glare, darkness);
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299
                + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class Result {
        private final double quality;
        private final double sharpness;
        private final double glare;
        private final double darkness;

        Result(double quality, double sharpness, double glare, double darkness) {
            this.quality = quality;
            this.sharpness = sharpness;
            this.glare = glare;
            this.darkness = darkness;
        }

        private static Result bad() {
            return new Result(0, 0, 0, 0);
        }

        public double quality() { return quality; }
        public double sharpness() { return sharpness; }
        public double glare() { return glare; }
        public double darkness() { return darkness; }

        public boolean worthOcr() {
            return quality >= .15 && glare < .58;
        }

        public boolean strongFrame() {
            return quality >= .28 && glare < .45;
        }
    }
}
