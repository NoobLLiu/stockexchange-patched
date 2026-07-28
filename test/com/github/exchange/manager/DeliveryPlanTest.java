package com.github.exchange.manager;

import java.util.Arrays;

public final class DeliveryPlanTest {
    public static void main(String[] args) {
        assert Arrays.equals(DeliveryPlan.chunks(140, 64), new int[] {64, 64, 12});
        assert Arrays.equals(DeliveryPlan.chunks(150, 64), new int[] {64, 64, 22});
        assert Arrays.stream(DeliveryPlan.chunks(2304, 64)).sum() == 2304;
        assert DeliveryPlan.chunks(0, 64).length == 0;
    }
}
