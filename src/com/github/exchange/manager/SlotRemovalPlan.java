package com.github.exchange.manager;

final class SlotRemovalPlan {
    static final int STORAGE_SLOT_COUNT = 36;

    private SlotRemovalPlan() {
    }

    static int[] create(int[] available, int quantity) {
        if (available == null || available.length != STORAGE_SLOT_COUNT || quantity <= 0) {
            return null;
        }

        int[] removals = new int[STORAGE_SLOT_COUNT];
        int remaining = quantity;
        for (int slot = 0; slot < available.length && remaining > 0; ++slot) {
            int slotAmount = Math.max(0, available[slot]);
            int removeAmount = Math.min(slotAmount, remaining);
            removals[slot] = removeAmount;
            remaining -= removeAmount;
        }
        return remaining == 0 ? removals : null;
    }
}
