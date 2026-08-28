package com.cardlens.live;

import android.graphics.Bitmap;

/**
 * Keeps only the clearest recent frame instead of retaining a large bitmap ring-buffer.
 *
 * This gives CardLens the practical benefit of a rolling best-frame buffer with far less memory,
 * GC pressure and heat. The retained frame is downscaled and replaced only when a meaningfully
 * sharper frame arrives.
 */
public final class BestFrameBuffer {
    private static final long WINDOW_MS = 1100;
    private static final int MAX_STORED_WIDTH = 360;
    private static final double MIN_STORE_QUALITY = .17;
    private static final double REPLACE_MARGIN = .035;

    private Bitmap bestFrame;
    private double bestQuality;
    private long windowStartedAt;
    private long bestAt;

    public void offer(Bitmap frame, double quality, long now) {
        if (frame == null || frame.isRecycled() || quality < MIN_STORE_QUALITY) return;

        if (bestFrame == null || now - windowStartedAt > WINDOW_MS) {
            clear();
            windowStartedAt = now;
        }

        boolean replace = bestFrame == null
                || quality >= bestQuality + REPLACE_MARGIN
                || now - bestAt > 760;
        if (!replace) return;

        Bitmap copy = scaledCopy(frame);
        if (copy == null) return;

        if (bestFrame != null && !bestFrame.isRecycled()) bestFrame.recycle();
        bestFrame = copy;
        bestQuality = quality;
        bestAt = now;
    }

    public boolean hasFreshFrame(long now) {
        return bestFrame != null
                && !bestFrame.isRecycled()
                && now - bestAt <= WINDOW_MS
                && bestQuality >= MIN_STORE_QUALITY;
    }

    public double bestQuality() {
        return bestQuality;
    }

    public VisualMatcher.LiveSignature createSignature(long now) {
        if (!hasFreshFrame(now)) return null;
        return VisualMatcher.fromLiveFrame(bestFrame);
    }

    public void clear() {
        if (bestFrame != null && !bestFrame.isRecycled()) bestFrame.recycle();
        bestFrame = null;
        bestQuality = 0;
        windowStartedAt = 0;
        bestAt = 0;
    }

    private Bitmap scaledCopy(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return null;

        int targetWidth = Math.min(MAX_STORED_WIDTH, width);
        int targetHeight = Math.max(1, Math.round(height * (targetWidth / (float) width)));

        if (targetWidth == width && targetHeight == height) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }
}
