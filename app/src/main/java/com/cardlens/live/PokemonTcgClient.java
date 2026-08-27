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

public final class PokemonTcgClient {
    private static final String TAG = "PokemonTcgClient";
    private static final String API = "https://api.pokemontcg.io/v2/cards";

    public MarketCard lookup(CardNumberParser.Candidate candidate, String ocrText) throws Exception {
        String q = "number:" + candidate.collectorNumber() + " set.printedTotal:" + candidate.printedTotal();
        String url = API + "?q=" + Uri.encode(q) + "&pageSize=12" +
                "&select=id,name,number,rarity,set,tcgplayer";

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3500);
        connection.setUseCaches(true);
        connection.setRequestProperty("Accept", "application/json");
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
            int score = 10;
            String normalizedName = normalize(name);
            String normalizedSet = normalize(setName);
            if (!normalizedName.isEmpty() && normalizedOcr.contains(normalizedName)) score += 8;
            if (!normalizedSet.isEmpty() && normalizedOcr.contains(normalizedSet)) score += 3;
            for (String token : normalizedName.split(" ")) {
                if (token.length() >= 4 && normalizedOcr.contains(token)) score += 1;
            }
            matches.add(new ScoredCard(card, score));
        }

        matches.sort(Comparator.comparingInt(ScoredCard::score).reversed());
        ScoredCard best = matches.get(0);
        int runnerUp = matches.size() > 1 ? matches.get(1).score() : 0;
        double confidence = matches.size() == 1
                ? 0.98
                : Math.min(0.96, 0.62 + Math.max(0, best.score() - runnerUp) * 0.06);

        if (matches.size() > 1 && best.score() - runnerUp < 2) {
            Log.i(TAG, "Ambiguous match for " + candidate.key() + ": " + matches.size() + " candidates");
            return null;
        }

        return toMarketCard(best.card(), confidence);
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
        private final int score;
        ScoredCard(JSONObject card, int score) { this.card = card; this.score = score; }
        JSONObject card() { return card; }
        int score() { return score; }
    }
}
