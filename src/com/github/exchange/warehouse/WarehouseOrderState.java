package com.github.exchange.warehouse;

import com.github.exchange.model.Order;

public final class WarehouseOrderState {
    private WarehouseOrderState() {
    }

    public static int totalQuantity(int filledQuantity, int remainingQuantity) {
        if (filledQuantity < 0 || remainingQuantity < 0) {
            throw new IllegalArgumentException("warehouse quantities must be non-negative");
        }
        return Math.addExact(filledQuantity, remainingQuantity);
    }

    public static Order.OrderStatus activeStatus(int filledQuantity, int remainingQuantity) {
        int total = totalQuantity(filledQuantity, remainingQuantity);
        if (remainingQuantity <= 0 || total <= 0) {
            return Order.OrderStatus.CANCELLED;
        }
        return filledQuantity > 0 ? Order.OrderStatus.PARTIAL : Order.OrderStatus.OPEN;
    }

    public static boolean needsResize(
        int orderRemaining,
        int escrowQuantity,
        int physicalRemaining
    ) {
        if (orderRemaining < 0 || escrowQuantity < 0 || physicalRemaining < 0) {
            throw new IllegalArgumentException("warehouse quantities must be non-negative");
        }
        return orderRemaining != physicalRemaining
            || escrowQuantity != physicalRemaining;
    }
}
