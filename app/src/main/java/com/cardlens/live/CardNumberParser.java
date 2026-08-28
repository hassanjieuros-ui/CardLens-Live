package com.cardlens.live;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardNumberParser {
    private static final Pattern FRACTION = Pattern.compile(
            "(?i)(?<![A-Z0-9])([A-Z]{0,4}\\s*\\d{1,3}[A-Z]?)\\s*[/|]\\s*(\\d{2,3})(?![A-Z0-9])"
    );

    public static final class Candidate {
        private final String collectorNumber;
        private final int printedTotal;
        private final String key;

        Candidate(String collectorNumber, int printedTotal) {
            this.collectorNumber = collectorNumber;
            this.printedTotal = printedTotal;
            this.key = collectorNumber + "/" + printedTotal;
        }

        public String collectorNumber() { return collectorNumber; }
        public int printedTotal() { return printedTotal; }
        public String key() { return key; }

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
                // as 111/10 is far outside any useful ratio. Reject only extreme numeric outliers so
                // normal secret-rare numbering like 148/142 or 205/165 still works.
                if (numeric > total * 4 && numeric - total > 30) continue;
                if (total < 15 && numeric > total + 25) continue;
            }
            return Optional.of(new Candidate(number, total));
        }
        return Optional.empty();
    }
}
