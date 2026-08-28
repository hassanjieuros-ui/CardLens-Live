package com.cardlens.live;

import java.util.Locale;

public final class MarketCard {
    private final String id;
    private final String name;
    private final String setName;
    private final String number;
    private final String rarity;
    private final double marketLow;
    private final double marketHigh;
    private final int priceVariants;
    private final String priceUpdatedAt;
    private final double confidence;
    private final String language;

    public MarketCard(String id, String name, String setName, String number, String rarity,
                      double marketLow, double marketHigh, int priceVariants,
                      String priceUpdatedAt, double confidence) {
        this(id, name, setName, number, rarity, marketLow, marketHigh, priceVariants,
                priceUpdatedAt, confidence, "EN");
    }

    public MarketCard(String id, String name, String setName, String number, String rarity,
                      double marketLow, double marketHigh, int priceVariants,
                      String priceUpdatedAt, double confidence, String language) {
        this.id = id;
        this.name = name;
        this.setName = setName;
        this.number = number;
        this.rarity = rarity;
        this.marketLow = marketLow;
        this.marketHigh = marketHigh;
        this.priceVariants = priceVariants;
        this.priceUpdatedAt = priceUpdatedAt;
        this.confidence = confidence;
        this.language = language == null || language.trim().isEmpty() ? "EN" : language;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String setName() { return setName; }
    public String number() { return number; }
    public String rarity() { return rarity; }
    public double marketLow() { return marketLow; }
    public double marketHigh() { return marketHigh; }
    public int priceVariants() { return priceVariants; }
    public String priceUpdatedAt() { return priceUpdatedAt; }
    public double confidence() { return confidence; }
    public String language() { return language; }

    public boolean hasPrice() { return marketHigh > 0.0; }

    public double conservativeMarket() {
        return marketLow > 0 ? marketLow : marketHigh;
    }

    public String marketLabel() {
        if (!hasPrice()) return "Market unavailable";
        if (priceVariants <= 1 || Math.abs(marketHigh - marketLow) < 0.01) {
            return String.format(Locale.US, "TCG Market  $%.2f", marketHigh);
        }
        return String.format(Locale.US, "TCG Market  $%.2f–$%.2f", marketLow, marketHigh);
    }

    public String maxBidLabel(double fraction) {
        if (!hasPrice()) return "—";
        return String.format(Locale.US, "$%.2f", conservativeMarket() * fraction);
    }
}
