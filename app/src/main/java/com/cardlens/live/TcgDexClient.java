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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Japanese-first card lookup backed by TCGdex. */
public final class TcgDexClient {
    private static final String TAG = "TcgDexClient";
    private static final String API = "https://api.tcgdex.net/v2/ja/cards";
    private static final int MAX_BRIEFS = 8;
    private static final int MAX_VISUAL = 6;
    private static final int MAX_DETAILS = 5;

    private final Map<String, JSONArray> briefCache =
            new LinkedHashMap<String, JSONArray>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> eldest) {
                    return size() > 64;
                }
            };
    private final Map<String, JSONObject> detailCache =
            new LinkedHashMap<String, JSONObject>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                    return size() > 160;
                }
            };

    public MarketCard lookup(CardNumberParser.Candidate candidate, String ocrText,
                             VisualMatcher.LiveSignature liveSignature) throws Exception {
        List<ScoredCard> candidates = loadBriefCandidates(candidate.collectorNumber(), ocrText);
        if (candidates.isEmpty()) return null;

        scoreArtwork(candidates, liveSignature);
        candidates.sort(Comparator.comparingDouble(ScoredCard::preDetailScore).reversed());

        List<ScoredCard> detailed = new ArrayList<>();
        int detailCount = Math.min(MAX_DETAILS, candidates.size());
        for (int i = 0; i < detailCount; i++) {
            ScoredCard scored = candidates.get(i);
            JSONObject full = fetchDetail(scored.id());
            if (full == null) continue;
            scored.setFull(full);
            scored.setDenominatorScore(denominatorScore(full, candidate.printedTotal()));
            double visual = scored.visualScore() >= 0 ? scored.visualScore() : 0;
            double combined = visual * .76 + scored.textScore() * .14 + scored.denominatorScore() * .10;
            scored.setCombinedScore(combined);
            detailed.add(scored);
        }

        if (detailed.isEmpty()) return null;
        detailed.sort(Comparator.comparingDouble(ScoredCard::combinedScore).reversed());
        ScoredCard best = detailed.get(0);
        ScoredCard runner = detailed.size() > 1 ? detailed.get(1) : null;
        double gap = runner == null ? 1.0 : best.combinedScore() - runner.combinedScore();

        Log.d(TAG, String.format(Locale.US,
                "JA match %s visual=%.3f text=%.3f denom=%.2f gap=%.3f",
                candidate.key(), best.visualScore(), best.textScore(), best.denominatorScore(), gap));

        if (best.visualScore() >= 0 && best.visualScore() < .30) return null;
        if (runner != null && gap < .040) return null;

        double confidence = best.visualScore() >= 0
                ? Math.min(.98, .56 + best.visualScore() * .34 + Math.min(.08, Math.max(0, gap) * 1.5))
                : Math.min(.84, .60 + best.textScore() * .18 + best.denominatorScore() * .06);

        return toMarketCard(best.full(), confidence);
    }

    private List<ScoredCard> loadBriefCandidates(String localId, String ocrText) throws Exception {
        JSONArray array;
        synchronized (briefCache) {
            array = briefCache.get(localId);
        }
        if (array == null) {
            String url = API + "?localId=" + Uri.encode(localId);
            String body = get(url, 1700, 2300);
            array = new JSONArray(body);
            synchronized (briefCache) { briefCache.put(localId, array); }
        }

        String normalizedOcr = LanguageUtil.normalizeSearch(ocrText);
        List<ScoredCard> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject brief = array.optJSONObject(i);
            if (brief == null) continue;
            String candidateLocal = brief.optString("localId", "");
            if (!sameLocalId(candidateLocal, localId)) continue;
            String id = brief.optString("id", "");
            if (id.isEmpty()) continue;
            String name = brief.optString("name", "");
            String normalizedName = LanguageUtil.normalizeSearch(name);
            double text = (!normalizedName.isEmpty() && normalizedOcr.contains(normalizedName)) ? 1.0 : .25;
            out.add(new ScoredCard(brief, id, text));
        }

        out.sort(Comparator.comparingDouble(ScoredCard::textScore).reversed());
        if (out.size() > MAX_BRIEFS) return new ArrayList<>(out.subList(0, MAX_BRIEFS));
        return out;
    }

    private void scoreArtwork(List<ScoredCard> cards, VisualMatcher.LiveSignature liveSignature) {
        if (liveSignature == null || !liveSignature.isUsable() || cards.isEmpty()) return;
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
                try { cards.get(i).setVisualScore(futures.get(i).get(1600, TimeUnit.MILLISECONDS)); }
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
        String body = get(API + "/" + Uri.encode(id), 1700, 2300);
        JSONObject full = new JSONObject(body);
        synchronized (detailCache) { detailCache.put(id, full); }
        return full;
    }

    private static double denominatorScore(JSONObject full, int printedTotal) {
        JSONObject set = full.optJSONObject("set");
        JSONObject count = set != null ? set.optJSONObject("cardCount") : null;
        if (count == null) return .25;
        int official = count.optInt("official", -1);
        int total = count.optInt("total", -1);
        if (printedTotal == official || printedTotal == total) return 1.0;
        if (official > 0 && Math.abs(printedTotal - official) <= 2) return .35;
        return 0;
    }

    private static MarketCard toMarketCard(JSONObject card, double confidence) {
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
                String key = keys.next();
                Object value = tcg.opt(key);
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
                min, max, variants, updated, confidence, "JP"
        );
    }

    private static String imageUrl(JSONObject brief) {
        String base = brief.optString("image", "");
        if (base.isEmpty()) return "";
        if (base.endsWith(".jpg") || base.endsWith(".png") || base.endsWith(".webp")) return base;
        return base + "/low.webp";
    }

    private static boolean sameLocalId(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.replaceAll("\\s+", "").toUpperCase(Locale.US);
        String nb = b.replaceAll("\\s+", "").toUpperCase(Locale.US);
        if (na.equals(nb)) return true;
        if (na.matches("\\d+") && nb.matches("\\d+")) {
            try { return Integer.parseInt(na) == Integer.parseInt(nb); }
            catch (NumberFormatException ignored) {}
        }
        return false;
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
            if (code < 200 || code >= 300) throw new IllegalStateException("TCGdex HTTP " + code);
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
        private double denominatorScore;
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
        double denominatorScore() { return denominatorScore; }
        double combinedScore() { return combinedScore; }
        JSONObject full() { return full; }
        double preDetailScore() { return (visualScore >= 0 ? visualScore * .82 : 0) + textScore * .18; }
        void setVisualScore(double value) { visualScore = value; }
        void setDenominatorScore(double value) { denominatorScore = value; }
        void setCombinedScore(double value) { combinedScore = value; }
        void setFull(JSONObject value) { full = value; }
    }
}
