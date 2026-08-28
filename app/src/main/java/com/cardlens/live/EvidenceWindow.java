package com.cardlens.live;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small rolling evidence window for shaky live-video scans.
 *
 * OCR no longer has to succeed perfectly on one frame. CardLens can combine a name from one frame,
 * a collector number from another and a better set read from a third, as long as they occur inside
 * the same short live-auction window.
 */
public final class EvidenceWindow {
    private static final long WINDOW_MS = 1650;
    private static final int MAX_ENTRIES = 9;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public void record(String key, String text, double quality, long now) {
        prune(now);
        entries.addLast(new Entry(key == null ? "" : key,
                text == null ? "" : text.trim(), quality, now));
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    public int votes(String key, long now) {
        prune(now);
        if (key == null || key.isEmpty()) return 0;
        int count = 0;
        for (Entry entry : entries) {
            if (key.equals(entry.key)) count++;
        }
        return count;
    }

    public double bestQuality(String key, long now) {
        prune(now);
        double best = 0;
        for (Entry entry : entries) {
            if (entry.key.isEmpty() || entry.key.equals(key)) {
                best = Math.max(best, entry.quality);
            }
        }
        return best;
    }

    public String mergedText(String key, long now) {
        prune(now);
        Set<String> unique = new LinkedHashSet<>();
        for (Entry entry : entries) {
            // Text-only frames are useful supporting evidence. Frames that explicitly point to a
            // different collector number are excluded so one noisy auction frame cannot pollute the
            // active candidate's name/set context.
            if (!entry.key.isEmpty() && !entry.key.equals(key)) continue;
            if (!entry.text.isEmpty()) unique.add(entry.text);
        }

        StringBuilder merged = new StringBuilder();
        for (String value : unique) {
            if (merged.length() > 0) merged.append('\n');
            merged.append(value);
            if (merged.length() > 900) break;
        }
        return merged.toString();
    }

    public void clear() {
        entries.clear();
    }

    private void prune(long now) {
        while (!entries.isEmpty() && now - entries.peekFirst().at > WINDOW_MS) {
            entries.removeFirst();
        }
    }

    private static final class Entry {
        private final String key;
        private final String text;
        private final double quality;
        private final long at;

        Entry(String key, String text, double quality, long at) {
            this.key = key;
            this.text = text;
            this.quality = quality;
            this.at = at;
        }
    }
}
