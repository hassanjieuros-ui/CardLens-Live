package com.cardlens.live;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardNumberParser {
    private static final Pattern FRACTION = Pattern.compile(
            "(?i)(?<![A-Z0-9])([A-Z]{0,4}\\s*\\d{1,3}[A-Z]?)\\s*[/|]\\s*(\\d{2,3})(?![A-Z0-9])"
    );
    private static final Pattern HP = Pattern.compile(
            "(?i)(?:\\bHP\\s*\\d{2,3}\\b|\\b\\d{2,3}\\s*HP\\b|\\b\\d{2,3}HP\\b)"
    );

    private static final String[] UI_WORDS = {
            "cardlens", "screenwork", "sudden death", "auction", "free pickup",
            "custom", "winning", "bid $", "tracking", "motion tolerance", "best frame",
            "live scan", "market", "instagram", "follower goal"
    };

    private static final String[] BODY_WORDS = {
            "damage", "opponent", "attack", "energy", "weakness", "resistance", "retreat",
            "during", "your ", "pokemon", "pokémon", "ability", "trainer", "supporter",
            "search ", "deck", "turn", "benched", "active pok", "attached", "discard",
            "draw ", "this card", "these cards"
    };

    public static final class Candidate {
        private final String collectorNumber;
        private final int printedTotal;
        private final String key;
        private final boolean nameCandidate;

        Candidate(String collectorNumber, int printedTotal) {
            this.collectorNumber = collectorNumber;
            this.printedTotal = printedTotal;
            this.key = collectorNumber + "/" + printedTotal;
            this.nameCandidate = false;
        }

        private Candidate(String nameHint) {
            this.collectorNumber = nameHint;
            this.printedTotal = 0;
            this.key = "NAME:" + LanguageUtil.normalizeSearch(nameHint);
            this.nameCandidate = true;
        }

        public String collectorNumber() { return collectorNumber; }
        public int printedTotal() { return printedTotal; }
        public String key() { return key; }
        public boolean isNameCandidate() { return nameCandidate; }
        public String nameHint() { return nameCandidate ? collectorNumber : ""; }

        @Override public String toString() { return key; }
    }

    private CardNumberParser() {}

    public static Optional<Candidate> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Optional.empty();

        Matcher matcher = FRACTION.matcher(text.toUpperCase(Locale.US));
        while (matcher.find()) {
            String number = matcher.group(1).replaceAll("\\s+", "");
            int total;
            try {
                total = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (total < 10 || total > 999) continue;
            if (number.matches("\\d+")) {
                int numeric = Integer.parseInt(number);
                if (numeric <= 0 || numeric > 999) continue;

                // Secret rares can legitimately exceed a set's printed total, but OCR garbage such
                // as 111/10 is far outside any useful ratio.
                if (numeric > total * 4 && numeric - total > 30) continue;
                if (total < 15 && numeric > total + 25) continue;
            }
            return Optional.of(new Candidate(number, total));
        }

        String nameHint = extractLikelyCardName(text);
        if (!nameHint.isEmpty()) return Optional.of(new Candidate(nameHint));
        return Optional.empty();
    }

    static String extractLikelyCardName(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String[] lines = text.split("\\r?\\n");
        String best = "";
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i] == null ? "" : lines[i].trim();
            if (raw.length() < 2 || raw.length() > 60) continue;
            String lowerRaw = raw.toLowerCase(Locale.ROOT);
            if (containsAny(lowerRaw, UI_WORDS) || raw.contains("$") || raw.contains("@")) continue;

            boolean japanese = LanguageUtil.containsJapanese(raw);
            boolean hadHp = HP.matcher(raw).find();

            String cleaned = raw
                    .replaceAll("(?i)^\\s*(?:BASIC|STAGE\\s*[12])\\s*", "")
                    .replaceAll("(?i)^\\s*たね\\s*", "");
            cleaned = HP.matcher(cleaned).replaceAll(" ");
            cleaned = cleaned.replaceAll("\\b\\d{1,3}\\b", " ");
            cleaned = cleaned.replaceAll("[^\\p{L}\\p{M}'’\\-\\.・ ]+", " ")
                    .replaceAll("\\s+", " ").trim();

            if (cleaned.length() < 3 || cleaned.length() > 30) continue;
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (containsAny(lower, BODY_WORDS) || containsAny(lower, UI_WORDS)) continue;

            int words = cleaned.split("\\s+").length;
            if (words > 4) continue;

            if (!japanese) {
                int first = cleaned.codePointAt(0);
                // Ordinary stream/UI prose tends to be lowercase. Card names are normally title
                // cased; an HP marker is strong enough evidence to relax that rule for noisy OCR.
                if (!Character.isUpperCase(first) && !hadHp) continue;
            }

            int score = 0;
            if (hadHp) score += 8;
            if (i <= 2) score += 4;
            else if (i <= 5) score += 2;
            if (japanese) score += 2;
            if (words <= 2) score += 2;
            if (cleaned.length() <= 16) score += 1;

            if (score > bestScore) {
                bestScore = score;
                best = cleaned;
            }
        }

        return bestScore >= 5 ? best : "";
    }

    private static boolean containsAny(String value, String[] needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
