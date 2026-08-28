package com.cardlens.live;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Name + artwork fallback for cards whose tiny collector number is unreadable on a live stream.
 * The card name only narrows the candidate pool; illustration matching still chooses the printing.
 */
public final class TcgDexNameClient {
    private static final String TAG = "TcgDexNameClient";
    private static final int MAX_VISUAL = 8;
    private static final int MAX_DETAILS = 4;

    private final String language;
    private final String api;
    private final Map<String, JSONArray> queryCache =
            new LinkedHashMap<String, JSONArray>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> eldest) {
                    return size() > 72;
                }
            };
    private final Map<String, JSONObject> detailCache =
            new LinkedHashMap<String, JSONObject>(48, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                    return size() > 144;
                }
            };

    public TcgDexNameClient(String language) {
        this.language = "ja".equalsIgnoreCase(language) ? "ja" : "en";
        this.api = "https://api.tcgdex.net/v2/" + this.language + "/cards";
    }

    public MarketCard lookup(String nameHint, String ocrText,
                             VisualMatcher.LiveSignature liveSignature) throws Exception {
        if (nameHint == null || nameHint.trim().isEmpty()) return null;
        if (liveSignature == null || !liveSignature.isUsable()) return null;

        List<ScoredCard> candidates = loadCandidates(nameHint, ocrText);
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(ScoredCard::textScore).reversed());
        scoreArtwork(candidates, liveSignature);
        for (ScoredCard card : candidates) {
            double visual = card.visualScore() >= 0 ? card.visualScore() : 0;
            card.setCombinedScore(visual * .78 + card.textScore() * .22);
        }
        candidates.sort(Comparator.comparingDouble(ScoredCard::combinedScore).reversed());

        List<ScoredCard> detailed = new ArrayList<>();
        int detailCount = Math.min(MAX_DETAILS, candidates.size());
        for (int i = 0; i < detailCount; i++) {
            ScoredCard scored = candidates.get(i);
            JSONObject full = fetchDetail(scored.id());
            if (full == null) continue;
            scored.setFull(full);
            detailed.add(scored);
        }
        if (detailed.isEmpty()) return null;

        detailed.sort(Comparator.comparingDouble(ScoredCard::combinedScore).reversed());
        ScoredCard best = detailed.get(0);
        ScoredCard runner = detailed.size() > 1 ? detailed.get(1) : null;
        double gap = runner == null ? 1.0 : best.combinedScore() - runner.combinedScore();

        Log.d(TAG, String.format(Locale.US,
                "%s name/art match %s visual=%.3f text=%.3f gap=%.3f",
                language, nameHint, best.visualScore(), best.textScore(), gap));

        // A readable name is useful evidence, but it is not enough to select a printing by itself.
        // Require the illustration to agree before exposing a price.
        if (best.visualScore() < .30) return null;
        if (runner != null && gap < .035) return null;

        double confidence = Math.min(.97,
                .58 + best.visualScore() * .31 + best.textScore() * .06
                        + Math.min(.02, Math.max(0, gap)));
        return toMarketCard(best.full(), confidence);
    }

    private List<ScoredCard> loadCandidates(String nameHint, String ocrText) throws Exception {
        Set<String> queries = new LinkedHashSet<>();
        String cleaned = nameHint.trim();
        queries.add(cleaned);

        // "Charizard ex" should still recover if OCR loses the suffix. We only broaden after the
        // exact hint, and artwork must still select the final printing.
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(" ex")) {
            queries.add(cleaned.substring(0, cleaned.length() - 3).trim());
        }

        Map<String, JSONObject> unique = new LinkedHashMap<>();
        for (String query : queries) {
            JSONArray array = fetchQuery(query);
            for (int i = 0; i < array.length() && unique.size() < 28; i++) {
                JSONObject brief = array.optJSONObject(i);
                if (brief == null) continue;
                String id = brief.optString("id", "");
                if (!id.isEmpty()) unique.put(id, brief);
            }
            if (!unique.isEmpty()) break;
        }

        String normalizedOcr = LanguageUtil.normalizeSearch(ocrText);
        String normalizedHint = LanguageUtil.normalizeSearch(nameHint);
        List<ScoredCard> out = new ArrayList<>();
        for (JSONObject brief : unique.values()) {
            String id = brief.optString("id", "");
            String name = brief.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            String normalizedName = LanguageUtil.normalizeSearch(name);
            double text;
            if (normalizedName.equals(normalizedHint)) text = 1.0;
            else if (!normalizedName.isEmpty() && normalizedOcr.contains(normalizedName)) text = .94;
            else if (!normalizedHint.isEmpty()
                    && (normalizedName.contains(normalizedHint) || normalizedHint.contains(normalizedName))) {
                text = .76;
            } else text = .40;
            out.add(new ScoredCard(brief, id, text));
        }
        return out;
    }

    private JSONArray fetchQuery(String name) throws Exception {
        String key = name.toLowerCase(Locale.ROOT).trim();
        synchronized (queryCache) {
            JSONArray cached = queryCache.get(key);
            if (cached != null) return cached;
        }
        String body = get(api + "?name=" + Uri.encode(name), 1500, 2100);
        JSONArray array = new JSONArray(body);
        synchronized (queryCache) { queryCache.put(key, array); }
        return array;
    }

    private void scoreArtwork(List<ScoredCard> cards, VisualMatcher.LiveSignature liveSignature) {
        if (cards.isEmpty()) return;
        int count = Math.min(MAX_VISUAL, cards.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, count));
        List<Future<Double>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String image = imageUrl(cards.get(i).brief());
                futures.add(pool.submit(() -> {
                    try { return VisualMatcher.scoreUrl(liveSignature, image); }
                    catch (Exception e) { return -1.0; }
                }));
            }
            for (int i = 0; i < count; i++) {
                try { cards.get(i).setVisualScore(futures.get(i).get(1500, TimeUnit.MILLISECONDS)); }
                catch (Exception e) {
                    futures.get(i).cancel(true);
                    cards.get(i).setVisualScore(-1);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private JSONObject fetchDetail(String id) throws Exception {
        synchronized (detailCache) {
            JSONObject cached = detailCache.get(id);
            if (cached != null) return cached;
        }
        String body = get(api + "/" + Uri.encode(id), 1500, 2100);
        JSONObject full = new JSONObject(body);
        synchronized (detailCache) { detailCache.put(id, full); }
        return full;
    }

    private MarketCard toMarketCard(JSONObject card, double confidence) {
        JSONObject set = card.optJSONObject("set");
        JSONObject pricing = card.optJSONObject("pricing");
        JSONObject tcg = pricing != null ? pricing.optJSONObject("tcgplayer") : null;
        double min = Double.MAX_VALUE;
        double max = 0;
        int variants = 0;
        String updated = tcg != null ? String.valueOf(tcg.opt("updated")) : "";

        if (tcg != null) {
            java.util.Iterator<String> keys = tcg.keys();
            while (keys.hasNext()) {
                Object value = tcg.opt(keys.next());
                if (!(value instanceof JSONObject)) continue;
                JSONObject variant = (JSONObject) value;
                double market = variant.optDouble("marketPrice", 0);
                if (market <= 0) continue;
                min = Math.min(min, market);
                max = Math.max(max, market);
                variants++;
            }
        }
        if (min == Double.MAX_VALUE) min = 0;

        return new MarketCard(
                card.optString("id", ""),
                card.optString("name", "Unknown card"),
                set != null ? set.optString("name", "Unknown set") : "Unknown set",
                card.optString("localId", ""),
                card.optString("rarity", ""),
                min, max, variants, updated, confidence,
                "ja".equals(language) ? "JP" : "EN"
        );
    }

    private static String imageUrl(JSONObject brief) {
        String base = brief.optString("image", "");
        if (base.isEmpty()) return "";
        if (base.endsWith(".jpg") || base.endsWith(".png") || base.endsWith(".webp")) return base;
        return base + "/low.webp";
    }

    private static String get(String url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "keep-alive");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("TCGdex HTTP " + code);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static final class ScoredCard {
        private final JSONObject brief;
        private final String id;
        private final double textScore;
        private double visualScore = -1;
        private double combinedScore;
        private JSONObject full;

        ScoredCard(JSONObject brief, String id, double textScore) {
            this.brief = brief;
            this.id = id;
            this.textScore = textScore;
        }
        JSONObject brief() { return brief; }
        String id() { return id; }
        double textScore() { return textScore; }
        double visualScore() { return visualScore; }
        double combinedScore() { return combinedScore; }
        JSONObject full() { return full; }
        void setVisualScore(double value) { visualScore = value; }
        void setCombinedScore(double value) { combinedScore = value; }
        void setFull(JSONObject value) { full = value; }
    }
}
