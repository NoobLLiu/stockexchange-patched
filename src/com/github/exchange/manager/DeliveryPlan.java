package com.github.exchange.manager;

public final class DeliveryPlan {
    private DeliveryPlan() {
    }

    public static int[] chunks(int quantity, int maxStackSize) {
        if (quantity <= 0 || maxStackSize <= 0) {
            return new int[0];
        }
        int chunkCount = (quantity + maxStackSize - 1) / maxStackSize;
        int[] chunks = new int[chunkCount];
        int remaining = quantity;
        for (int i = 0; i < chunks.length; ++i) {
            int chunk = Math.min(maxStackSize, remaining);
            chunks[i] = chunk;
            remaining -= chunk;
        }
        return chunks;
    }
}
