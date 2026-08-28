package com.cardlens.live;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Cheap frame scoring used before OCR/visual matching.
 *
 * v0.8.1 evaluates both a broad center region and a tighter likely-card region, then keeps the
 * better result. That prevents animated Whatnot UI/background areas from making a readable card
 * look unusable. Thresholds are intentionally permissive; artwork matching is the final safety gate.
 */
public final class FrameQuality {
    private FrameQuality() {}

    public static Result analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 24 || bitmap.getHeight() < 24) {
            return Result.bad();
        }

        Result broad = analyzeRegion(bitmap, .10f, .08f, .90f, .88f);
        Result card = analyzeRegion(bitmap, .22f, .02f, .78f, .78f);

        // Prefer the tighter card-region read when it is clearer, but keep the broad region as a
        // fallback for sellers holding cards slightly off center.
        return card.quality() >= broad.quality() ? card : broad;
    }

    private static Result analyzeRegion(Bitmap bitmap, float leftPct, float topPct,
                                        float rightPct, float bottomPct) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.round(width * leftPct);
        int right = Math.round(width * rightPct);
        int top = Math.round(height * topPct);
        int bottom = Math.round(height * bottomPct);

        int stepX = Math.max(2, (right - left) / 34);
        int stepY = Math.max(2, (bottom - top) / 50);

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

                if (c >= 247) bright++;
                if (c <= 15) dark++;
            }
        }

        if (samples == 0 || edgeCount == 0) return Result.bad();

        double normalizedEdge = (edgeSum / (double) edgeCount) / 255.0;
        double glare = bright / (double) samples;
        double darkness = dark / (double) samples;

        // 640-wide live video is softer than a still image. The old threshold was rejecting
        // compressed but readable auction frames. Keep the blur gate cheap and permissive here;
        // downstream multi-frame + artwork matching still rejects bad identities.
        double sharpness = clamp((normalizedEdge - .010) / .105);
        double glarePenalty = 1.0 - Math.min(.65, glare * 1.25);
        double darkPenalty = 1.0 - Math.min(.50, darkness * .95);
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
            return quality >= .08 && glare < .68;
        }

        public boolean strongFrame() {
            return quality >= .18 && glare < .55;
        }
    }
}
