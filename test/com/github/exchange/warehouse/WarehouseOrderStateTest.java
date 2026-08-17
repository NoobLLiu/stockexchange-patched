package com.github.exchange.warehouse;

import com.github.exchange.model.Order;

public final class WarehouseOrderStateTest {
    public static void main(String[] args) {
        assert WarehouseOrderState.totalQuantity(0, 3456) == 3456;
        assert WarehouseOrderState.totalQuantity(17, 39) == 56;
        assert WarehouseOrderState.activeStatus(0, 64) == Order.OrderStatus.OPEN;
        assert WarehouseOrderState.activeStatus(7, 57) == Order.OrderStatus.PARTIAL;
        assert WarehouseOrderState.activeStatus(7, 0) == Order.OrderStatus.CANCELLED;
        assert !WarehouseOrderState.needsResize(39, 39, 39);
        assert WarehouseOrderState.needsResize(38, 39, 39);
        assert WarehouseOrderState.needsResize(39, 38, 39);

        boolean rejected = false;
        try {
            WarehouseOrderState.totalQuantity(-1, 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assert rejected;
    }
}
