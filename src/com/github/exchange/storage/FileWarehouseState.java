package com.github.exchange.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FileWarehouseState {
    static final int VERSION = 1;

    private FileWarehouseState() {
    }

    static List<Map<String, Object>> encode(Map<String, Integer> quantities) {
        if (quantities == null) {
            throw new IllegalArgumentException(
                "warehouse state quantities must not be null"
            );
        }
        List<Map.Entry<String, Integer>> entries =
            new ArrayList<Map.Entry<String, Integer>>(quantities.entrySet());
        entries.sort(Comparator.comparing(
            Map.Entry::getKey,
            Comparator.nullsFirst(String::compareTo)
        ));

        List<Map<String, Object>> encoded =
            new ArrayList<Map<String, Object>>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            String itemBase64 = entry.getKey();
            Integer quantity = entry.getValue();
            if (itemBase64 == null || itemBase64.isBlank()) {
                throw new IllegalArgumentException(
                    "warehouse state item key must be non-blank"
                );
            }
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException(
                    "warehouse state quantity must be positive"
                );
            }
            Map<String, Object> serialized =
                new LinkedHashMap<String, Object>();
            serialized.put("item_base64", itemBase64);
            serialized.put("quantity", quantity);
            encoded.add(serialized);
        }
        return encoded;
    }

    static Map<String, Integer> decode(Object rawEntries) {
        if (!(rawEntries instanceof List<?> entries)) {
            throw new IllegalArgumentException(
                "warehouse state entries must be a list"
            );
        }
        Map<String, Integer> decoded =
            new LinkedHashMap<String, Integer>();
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                    "warehouse state contains a non-map entry"
                );
            }
            Object itemValue = entry.get("item_base64");
            Object quantityValue = entry.get("quantity");
            if (!(itemValue instanceof String itemBase64)
                || itemBase64.isBlank()) {
                throw new IllegalArgumentException(
                    "warehouse state item_base64 must be non-blank"
                );
            }
            if (!(quantityValue instanceof Number quantityNumber)) {
                throw new IllegalArgumentException(
                    "warehouse state quantity must be an integer"
                );
            }
            long quantityLong = quantityNumber.longValue();
            if (quantityLong <= 0L || quantityLong > Integer.MAX_VALUE
                || Double.compare(
                    quantityNumber.doubleValue(),
                    (double)quantityLong
                ) != 0) {
                throw new IllegalArgumentException(
                    "warehouse state quantity must be a positive integer"
                );
            }
            if (decoded.put(itemBase64, (int)quantityLong) != null) {
                throw new IllegalArgumentException(
                    "warehouse state contains a duplicate item"
                );
            }
        }
        return decoded;
    }
}
