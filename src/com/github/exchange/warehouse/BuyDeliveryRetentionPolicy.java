package com.github.exchange.warehouse;

public final class BuyDeliveryRetentionPolicy {
    private BuyDeliveryRetentionPolicy() {
    }

    public static boolean canDiscard(
        int remainingQuantity,
        boolean settlementPending
    ) {
        if (remainingQuantity < 0) {
            throw new IllegalArgumentException(
                "remaining delivery quantity must be non-negative"
            );
        }
        return remainingQuantity == 0 && !settlementPending;
    }
}
