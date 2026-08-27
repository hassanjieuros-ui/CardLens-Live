package com.cardlens.live;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight illustration matcher used by the live scanner.
 *
 * This deliberately avoids uploading captured frames. A compact visual signature is created
 * on-device from several likely card crops. Candidate reference images are downloaded from the
 * card-data provider, converted to signatures, and cached in memory.
 *
 * The signature combines a coarse RGB histogram with a perceptual dHash. It is not intended to be
 * the final visual-search engine; it is the first hybrid layer that makes artwork a major matching
 * signal while keeping the sudden-death pipeline fast and private.
 */
public final class VisualMatcher {
    private static final float CARD_ASPECT = 2.5f / 3.5f;
    private static final int HIST_BINS = 4;
    private static final int HIST_SIZE = HIST_BINS * HIST_BINS * HIST_BINS;

    private static final Map<String, ReferenceSignature> referenceCache =
            new LinkedHashMap<String, ReferenceSignature>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ReferenceSignature> eldest) {
                    return size() > 256;
                }
            };

    private VisualMatcher() {}

    public static LiveSignature fromLiveFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return LiveSignature.empty();

        List<Feature> features = new ArrayList<>();
        // Multiple centered crops make this tolerant of sellers holding the card nearer/farther
        // from the camera without running an expensive object detector on every frame.
        addCenteredCrop(features, frame, .46f);
        addCenteredCrop(features, frame, .58f);
        addCenteredCrop(features, frame, .70f);
        addCenteredCrop(features, frame, .82f);

        return new LiveSignature(features.toArray(new Feature[0]));
    }

    public static double scoreUrl(LiveSignature live, String imageUrl) throws Exception {
        if (live == null || !live.isUsable() || imageUrl == null || imageUrl.trim().isEmpty()) return -1;

        ReferenceSignature reference;
        synchronized (referenceCache) {
            reference = referenceCache.get(imageUrl);
        }

        if (reference == null) {
            Bitmap bitmap = downloadBitmap(imageUrl);
            if (bitmap == null) return -1;
            try {
                reference = fromReferenceCard(bitmap);
            } finally {
                bitmap.recycle();
            }
            synchronized (referenceCache) {
                referenceCache.put(imageUrl, reference);
            }
        }

        return compare(live, reference);
    }

    private static Bitmap downloadBitmap(String imageUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(1100);
        connection.setReadTimeout(1500);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "image/*");
        connection.setRequestProperty("Connection", "keep-alive");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static ReferenceSignature fromReferenceCard(Bitmap card) {
        List<Feature> features = new ArrayList<>();

        // Whole-card signature helps with full-art/SIR cards and distinctive borders/textures.
        features.add(feature(card));

        // Traditional cards concentrate the illustration in the upper/middle panel. Add two
        // generous artwork regions so the matcher is not tied to a single card-era layout.
        addCrop(features, card, .05f, .08f, .95f, .70f);
        addCrop(features, card, .03f, .13f, .97f, .82f);

        return new ReferenceSignature(features.toArray(new Feature[0]));
    }

    private static double compare(LiveSignature live, ReferenceSignature reference) {
        double best = -1;
        for (Feature a : live.features) {
            for (Feature b : reference.features) {
                double histogram = histogramIntersection(a.histogram, b.histogram);
                double hash = 1.0 - Long.bitCount(a.dHash ^ b.dHash) / 64.0;
                // Color/layout carries most of the signal; dHash adds structure without making
                // glare/compression destroy the score.
                double score = histogram * .72 + hash * .28;
                if (score > best) best = score;
            }
        }
        return best;
    }

    private static void addCenteredCrop(List<Feature> out, Bitmap source, float widthFraction) {
        int sourceW = source.getWidth();
        int sourceH = source.getHeight();
        int cropW = Math.max(32, Math.min(sourceW, Math.round(sourceW * widthFraction)));
        int cropH = Math.max(44, Math.round(cropW / CARD_ASPECT));
        if (cropH > sourceH) {
            cropH = sourceH;
            cropW = Math.max(32, Math.round(cropH * CARD_ASPECT));
        }

        int left = Math.max(0, (sourceW - cropW) / 2);
        // Slightly above geometric center because the Whatnot bid/UI area occupies the bottom.
        int centerY = Math.round(sourceH * .46f);
        int top = Math.max(0, Math.min(sourceH - cropH, centerY - cropH / 2));

        Bitmap crop = Bitmap.createBitmap(source, left, top, cropW, cropH);
        try {
            out.add(feature(crop));
        } finally {
            crop.recycle();
        }
    }

    private static void addCrop(List<Feature> out, Bitmap source,
                                float leftPct, float topPct, float rightPct, float bottomPct) {
        int left = Math.max(0, Math.round(source.getWidth() * leftPct));
        int top = Math.max(0, Math.round(source.getHeight() * topPct));
        int right = Math.min(source.getWidth(), Math.round(source.getWidth() * rightPct));
        int bottom = Math.min(source.getHeight(), Math.round(source.getHeight() * bottomPct));
        if (right - left < 24 || bottom - top < 24) return;

        Bitmap crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
        try {
            out.add(feature(crop));
        } finally {
            crop.recycle();
        }
    }

    private static Feature feature(Bitmap input) {
        Bitmap histogramBitmap = Bitmap.createScaledBitmap(input, 32, 32, true);
        float[] histogram = new float[HIST_SIZE];
        try {
            for (int y = 0; y < histogramBitmap.getHeight(); y++) {
                for (int x = 0; x < histogramBitmap.getWidth(); x++) {
                    int pixel = histogramBitmap.getPixel(x, y);
                    int r = Math.min(HIST_BINS - 1, Color.red(pixel) * HIST_BINS / 256);
                    int g = Math.min(HIST_BINS - 1, Color.green(pixel) * HIST_BINS / 256);
                    int b = Math.min(HIST_BINS - 1, Color.blue(pixel) * HIST_BINS / 256);
                    histogram[(r * HIST_BINS + g) * HIST_BINS + b] += 1f;
                }
            }
            float total = histogramBitmap.getWidth() * histogramBitmap.getHeight();
            for (int i = 0; i < histogram.length; i++) histogram[i] /= total;
        } finally {
            if (histogramBitmap != input) histogramBitmap.recycle();
        }

        Bitmap hashBitmap = Bitmap.createScaledBitmap(input, 9, 8, true);
        long hash = 0L;
        int bit = 0;
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int left = luminance(hashBitmap.getPixel(x, y));
                    int right = luminance(hashBitmap.getPixel(x + 1, y));
                    if (left > right) hash |= (1L << bit);
                    bit++;
                }
            }
        } finally {
            if (hashBitmap != input) hashBitmap.recycle();
        }

        return new Feature(histogram, hash);
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    private static double histogramIntersection(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.min(a[i], b[i]);
        return Math.max(0, Math.min(1, sum));
    }

    public static final class LiveSignature {
        private final Feature[] features;
        private LiveSignature(Feature[] features) { this.features = features; }
        private static LiveSignature empty() { return new LiveSignature(new Feature[0]); }
        public boolean isUsable() { return features.length > 0; }
    }

    private static final class ReferenceSignature {
        private final Feature[] features;
        ReferenceSignature(Feature[] features) { this.features = features; }
    }

    private static final class Feature {
        private final float[] histogram;
        private final long dHash;
        Feature(float[] histogram, long dHash) {
            this.histogram = histogram;
            this.dHash = dHash;
        }
    }
}
