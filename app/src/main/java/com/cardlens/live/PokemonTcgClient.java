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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PokemonTcgClient {
    private static final String TAG = "PokemonTcgClient";
    private static final String API = "https://api.pokemontcg.io/v2/cards";
    private static final int MAX_VISUAL_CANDIDATES = 4;

    public MarketCard lookup(CardNumberParser.Candidate candidate, String ocrText,
                             VisualMatcher.LiveSignature liveSignature) throws Exception {
        String q = "number:" + candidate.collectorNumber() + " set.printedTotal:" + candidate.printedTotal();
        String url = API + "?q=" + Uri.encode(q) + "&pageSize=10" +
                "&select=id,name,number,rarity,set,images,tcgplayer";

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(2400);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "keep-alive");
        if (!BuildConfig.POKEMON_TCG_API_KEY.trim().isEmpty()) {
            connection.setRequestProperty("X-Api-Key", BuildConfig.POKEMON_TCG_API_KEY);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (code == 429) throw new IllegalStateException("Pokémon TCG API rate limit reached");
        if (code < 200 || code >= 300) throw new IllegalStateException("Card API HTTP " + code);

        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) return null;

        String normalizedOcr = normalize(ocrText);
        List<ScoredCard> matches = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject card = data.getJSONObject(i);
            JSONObject set = card.optJSONObject("set");
            String name = card.optString("name", "Unknown card");
            String setName = set != null ? set.optString("name", "Unknown set") : "Unknown set";
            int textScore = 10;
            String normalizedName = normalize(name);
            String normalizedSet = normalize(setName);
            if (!normalizedName.isEmpty() && normalizedOcr.contains(normalizedName)) textScore += 8;
            if (!normalizedSet.isEmpty() && normalizedOcr.contains(normalizedSet)) textScore += 3;
            for (String token : normalizedName.split(" ")) {
                if (token.length() >= 4 && normalizedOcr.contains(token)) textScore += 1;
            }
            matches.add(new ScoredCard(card, textScore));
        }

        // OCR narrows the search space; artwork now does most of the actual disambiguation.
        matches.sort(Comparator.comparingInt(ScoredCard::textScore).reversed());
        scoreArtwork(matches, liveSignature);

        for (ScoredCard match : matches) {
            double textNorm = textNormalized(match.textScore());
            if (match.visualScore() >= 0) {
                match.setCombinedScore(match.visualScore() * .72 + textNorm * .28);
            } else {
                // Missing visual evidence is allowed as a fallback, but receives much less weight.
                match.setCombinedScore(textNorm * .28);
            }
        }

        matches.sort(Comparator.comparingDouble(ScoredCard::combinedScore).reversed());
        ScoredCard best = matches.get(0);
        ScoredCard runner = matches.size() > 1 ? matches.get(1) : null;
        double gap = runner == null ? 1.0 : best.combinedScore() - runner.combinedScore();
        double bestText = textNormalized(best.textScore());

        if (best.visualScore() >= 0) {
            Log.d(TAG, String.format(Locale.US,
                    "Hybrid match %s visual=%.3f text=%.3f gap=%.3f",
                    candidate.key(), best.visualScore(), bestText, gap));

            // Reject an OCR-generated candidate when the visible artwork disagrees strongly.
            if (best.visualScore() < .30 && bestText < .70) {
                Log.i(TAG, "Artwork rejected OCR candidate for " + candidate.key());
                return null;
            }

            // With multiple possible cards, require a meaningful visual/text lead rather than
            // letting the shared collector number decide the result.
            if (runner != null && gap < .045) {
                Log.i(TAG, "Visually ambiguous match for " + candidate.key());
                return null;
            }
        } else {
            // Network/image failure fallback keeps the scanner usable, but preserves the old
            // conservative ambiguity behavior when artwork cannot be checked.
            int runnerText = runner != null ? runner.textScore() : 0;
            if (runner != null && best.textScore() - runnerText < 2) {
                Log.i(TAG, "Text-only ambiguous match for " + candidate.key());
                return null;
            }
        }

        double confidence;
        if (best.visualScore() >= 0) {
            confidence = Math.min(.98,
                    .56 + best.visualScore() * .34 + Math.min(.08, Math.max(0, gap) * 1.5));
        } else {
            confidence = runner == null
                    ? .82
                    : Math.min(.88, .64 + Math.max(0, best.textScore() - runner.textScore()) * .05);
        }

        return toMarketCard(best.card(), confidence);
    }

    // Compatibility overload for any non-live callers/tests.
    public MarketCard lookup(CardNumberParser.Candidate candidate, String ocrText) throws Exception {
        return lookup(candidate, ocrText, null);
    }

    private static void scoreArtwork(List<ScoredCard> matches,
                                     VisualMatcher.LiveSignature liveSignature) {
        if (liveSignature == null || !liveSignature.isUsable() || matches.isEmpty()) return;

        int count = Math.min(MAX_VISUAL_CANDIDATES, matches.size());
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Future<Double>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ScoredCard match = matches.get(i);
                String imageUrl = imageUrl(match.card());
                futures.add(pool.submit(() -> {
                    try {
                        return VisualMatcher.scoreUrl(liveSignature, imageUrl);
                    } catch (Exception e) {
                        Log.d(TAG, "Visual reference unavailable: " + imageUrl, e);
                        return -1.0;
                    }
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    matches.get(i).setVisualScore(futures.get(i).get(1750, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    futures.get(i).cancel(true);
                    matches.get(i).setVisualScore(-1);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String imageUrl(JSONObject card) {
        JSONObject images = card.optJSONObject("images");
        if (images == null) return "";
        String small = images.optString("small", "");
        if (!small.trim().isEmpty()) return small;
        return images.optString("large", "");
    }

    private static double textNormalized(int score) {
        return Math.max(0, Math.min(1, (score - 10) / 12.0));
    }

    private static MarketCard toMarketCard(JSONObject card, double confidence) {
        JSONObject set = card.optJSONObject("set");
        JSONObject tcg = card.optJSONObject("tcgplayer");
        double min = Double.MAX_VALUE;
        double max = 0;
        int variants = 0;
        String updatedAt = tcg != null ? tcg.optString("updatedAt", "") : "";

        if (tcg != null) {
            JSONObject prices = tcg.optJSONObject("prices");
            if (prices != null) {
                java.util.Iterator<String> keys = prices.keys();
                while (keys.hasNext()) {
                    String finish = keys.next();
                    JSONObject p = prices.optJSONObject(finish);
                    if (p == null || !p.has("market") || p.isNull("market")) continue;
                    double market = p.optDouble("market", 0);
                    if (market <= 0) continue;
                    min = Math.min(min, market);
                    max = Math.max(max, market);
                    variants++;
                }
            }
        }

        if (min == Double.MAX_VALUE) min = 0;
        return new MarketCard(
                card.optString("id", ""),
                card.optString("name", "Unknown card"),
                set != null ? set.optString("name", "Unknown set") : "Unknown set",
                card.optString("number", ""),
                card.optString("rarity", ""),
                min,
                max,
                variants,
                updatedAt,
                confidence
        );
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

    private static String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class ScoredCard {
        private final JSONObject card;
        private final int textScore;
        private double visualScore = -1;
        private double combinedScore;

        ScoredCard(JSONObject card, int textScore) {
            this.card = card;
            this.textScore = textScore;
        }

        JSONObject card() { return card; }
        int textScore() { return textScore; }
        double visualScore() { return visualScore; }
        double combinedScore() { return combinedScore; }
        void setVisualScore(double visualScore) { this.visualScore = visualScore; }
        void setCombinedScore(double combinedScore) { this.combinedScore = combinedScore; }
    }
}
