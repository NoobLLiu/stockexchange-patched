package com.github.exchange.manager;

import java.util.Arrays;

public final class SlotRemovalPlanTest {
    public static void main(String[] args) {
        assertPlan(
            amounts(64, 64, 12),
            140,
            amounts(64, 64, 12),
            "Exact multi-stack removal must consume only the requested quantity"
        );
        assertPlan(
            amounts(64, 64, 64),
            150,
            amounts(64, 64, 22),
            "Partial final-stack removal must preserve the excess"
        );
        assert SlotRemovalPlan.create(amounts(64, 64), 129) == null
            : "Insufficient inventory must not produce a partial plan";
        assert SlotRemovalPlan.create(amounts(64), 0) == null
            : "Zero quantity must be rejected";
    }

    private static int[] amounts(int... values) {
        int[] result = new int[SlotRemovalPlan.STORAGE_SLOT_COUNT];
        System.arraycopy(values, 0, result, 0, values.length);
        return result;
    }

    private static void assertPlan(int[] available, int quantity, int[] expected, String message) {
        int[] actual = SlotRemovalPlan.create(available, quantity);
        assert Arrays.equals(actual, expected)
            : message + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual);
    }
}
