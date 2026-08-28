package com.cardlens.live;

import java.util.Locale;

public final class LanguageUtil {
    private LanguageUtil() {}

    public static boolean containsJapanese(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u309F') ||
                    (c >= '\u30A0' && c <= '\u30FF') ||
                    (c >= '\u4E00' && c <= '\u9FFF') ||
                    (c >= '\uFF66' && c <= '\uFF9D')) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeSearch(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "")
                .trim();
    }
}
