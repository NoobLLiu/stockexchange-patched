package com.github.exchange.storage;

import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class UnavailableStorageManagerTest {
    public static void main(String[] args) {
        StorageManager storage = new UnavailableStorageManager();
        assert storage.insertOrder(new Order()) == -1;
        assert !storage.updateOrder(new Order());
        assert storage.getOrder(1) == null;
        assert storage.getActiveOrdersByItem(1, Order.OrderType.SELL).isEmpty();
        assert !storage.insertEscrow(new EscrowEntry());
        assert storage.getEscrow(1, EscrowEntry.AssetType.ITEM) == null;
        assert !storage.addToMoneyWarehouse("player", BigDecimal.ONE);
        assert storage.getMoneyWarehouseBalance("player")
            .compareTo(BigDecimal.ZERO) == 0;
        assert !storage.takeFromMoneyWarehouse("player", BigDecimal.ONE);
        assert !storage.addToPlayerItemWarehouse("player", "item", 1);
        assert storage.getPlayerItemWarehouse("player").isEmpty();
        assert !storage.takeFromPlayerItemWarehouse("player", "item", 1);
        assert storage.getDailyRegisterCount("player", LocalDate.now()) == 0;
    }
}
