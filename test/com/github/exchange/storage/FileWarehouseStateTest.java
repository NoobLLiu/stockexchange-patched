package com.github.exchange.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FileWarehouseStateTest {
    public static void main(String[] args) {
        testRoundTripIsDeterministic();
        testEmptyStateRoundTrip();
        testInvalidStateIsRejected();
        System.out.println("FileWarehouseStateTest PASSED");
    }

    private static void testRoundTripIsDeterministic() {
        Map<String, Integer> state = new LinkedHashMap<String, Integer>();
        state.put("item-b", 2);
        state.put("item-a", 1);

        List<Map<String, Object>> encoded = FileWarehouseState.encode(state);
        assert encoded.size() == 2;
        assert "item-a".equals(encoded.get(0).get("item_base64"));
        assert "item-b".equals(encoded.get(1).get("item_base64"));
        assert FileWarehouseState.decode(encoded).equals(
            Map.of("item-a", 1, "item-b", 2)
        );
        assert FileWarehouseState.VERSION == 1;
    }

    private static void testEmptyStateRoundTrip() {
        List<Map<String, Object>> encoded =
            FileWarehouseState.encode(Map.of());
        assert encoded.isEmpty();
        assert FileWarehouseState.decode(encoded).isEmpty();
    }

    private static void testInvalidStateIsRejected() {
        expectFailure(() -> FileWarehouseState.encode(null));
        expectFailure(() -> FileWarehouseState.encode(Map.of("", 1)));
        expectFailure(() -> FileWarehouseState.encode(Map.of("item", 0)));
        expectFailure(() -> FileWarehouseState.decode("not-a-list"));
        expectFailure(() -> FileWarehouseState.decode(
            List.of(Map.of("item_base64", "", "quantity", 1))
        ));
        expectFailure(() -> FileWarehouseState.decode(
            List.of(Map.of("item_base64", "item", "quantity", 0))
        ));

        List<Map<String, Object>> duplicates =
            new ArrayList<Map<String, Object>>();
        duplicates.add(Map.of("item_base64", "item", "quantity", 1));
        duplicates.add(Map.of("item_base64", "item", "quantity", 2));
        expectFailure(() -> FileWarehouseState.decode(duplicates));
        expectFailure(() -> FileWarehouseState.decode(
            List.of(Map.of("item_base64", "item", "quantity", 1.5D))
        ));
        expectFailure(() -> FileWarehouseState.decode(
            List.of(Map.of(
                "item_base64",
                "item",
                "quantity",
                (long)Integer.MAX_VALUE + 1L
            ))
        ));
    }

    private static void expectFailure(Runnable action) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            failed = true;
        }
        assert failed;
    }
}
