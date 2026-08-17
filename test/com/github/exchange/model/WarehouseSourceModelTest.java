package com.github.exchange.model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

public final class WarehouseSourceModelTest {
    public static void main(String[] args) {
        String playerUuid = UUID.randomUUID().toString();
        String warehouseUuid = UUID.randomUUID().toString();
        Timestamp now = new Timestamp(System.currentTimeMillis());

        Order order = new Order(
            1,
            Order.OrderType.SELL,
            1,
            playerUuid,
            BigDecimal.ONE,
            1,
            0,
            Order.OrderStatus.OPEN,
            now,
            now
        );
        assert order.getSourceWarehouseId() == null;
        assert order.isPersistable();
        order.setSourceWarehouseId(warehouseUuid);
        assert order.isPersistable();
        Order warehouseOrder = new Order(
            2,
            Order.OrderType.SELL,
            1,
            playerUuid,
            BigDecimal.ONE,
            1,
            0,
            Order.OrderStatus.OPEN,
            now,
            now,
            warehouseUuid
        );
        assert warehouseUuid.equals(warehouseOrder.getSourceWarehouseId());
        assert warehouseOrder.isPersistable();
        order.setSourceWarehouseId("not-a-uuid");
        assert !order.isPersistable();

        EscrowEntry escrow = new EscrowEntry(
            1,
            playerUuid,
            EscrowEntry.AssetType.ITEM,
            BigDecimal.ZERO,
            "serialized-item",
            1
        );
        assert escrow.getSourceWarehouseId() == null;
        assert escrow.isPersistable();
        escrow.setSourceWarehouseId(warehouseUuid);
        assert escrow.isPersistable();
        EscrowEntry warehouseEscrow = new EscrowEntry(
            2,
            playerUuid,
            EscrowEntry.AssetType.ITEM,
            BigDecimal.ZERO,
            "serialized-item",
            1,
            warehouseUuid
        );
        assert warehouseUuid.equals(warehouseEscrow.getSourceWarehouseId());
        assert warehouseEscrow.isPersistable();
        escrow.setSourceWarehouseId("");
        assert !escrow.isPersistable();
    }
}
