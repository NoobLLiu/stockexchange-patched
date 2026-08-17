package com.github.exchange.warehouse;

public final class BuyDeliveryRetentionPolicyTest {
    public static void main(String[] args) {
        assert !BuyDeliveryRetentionPolicy.canDiscard(1, false);
        assert !BuyDeliveryRetentionPolicy.canDiscard(0, true);
        assert BuyDeliveryRetentionPolicy.canDiscard(0, false);
        boolean rejected = false;
        try {
            BuyDeliveryRetentionPolicy.canDiscard(-1, false);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assert rejected;
        System.out.println("BuyDeliveryRetentionPolicyTest PASSED");
    }
}
