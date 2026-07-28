package com.github.exchange.gui;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MarketListingSearch {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[\u00a7&][0-9A-FK-ORX]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MarketListingSearch() {
    }

    public static boolean matches(
        String query,
        int itemId,
        String displayName,
        String itemName,
        String material,
        String keyName,
        String typeName
    ) {
        String keyword = normalize(query);
        if (keyword.isEmpty()) {
            return true;
        }
        return contains(itemId, keyword)
            || contains(displayName, keyword)
            || contains(itemName, keyword)
            || contains(material, keyword)
            || contains(keyName, keyword)
            || contains(typeName, keyword);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String plain = COLOR_CODES.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
        plain = NON_ALPHANUMERIC.matcher(plain).replaceAll(" ").trim();
        return WHITESPACE.matcher(plain).replaceAll(" ");
    }

    private static boolean contains(Object value, String keyword) {
        return value != null && normalize(String.valueOf(value)).contains(keyword);
    }
}
