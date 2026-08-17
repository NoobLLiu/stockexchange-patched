package com.github.exchange.storage;

import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Rejects every storage operation after the plugin has entered fail-closed
 * mode. Returning this implementation prevents missed call-site guards from
 * mutating stale in-memory storage state.
 */
public final class UnavailableStorageManager implements StorageManager {
    @Override
    public void init() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public int insertExchangeItem(ExchangeItem item) {
        return -1;
    }

    @Override
    public void updateExchangeItem(ExchangeItem item) {
    }

    @Override
    public void deleteExchangeItem(int itemId) {
    }

    @Override
    public ExchangeItem getExchangeItem(int itemId) {
        return null;
    }

    @Override
    public ExchangeItem getExchangeItemByHash(String material, String nbtHash) {
        return null;
    }

    @Override
    public List<ExchangeItem> getAllExchangeItems() {
        return Collections.emptyList();
    }

    @Override
    public int insertOrder(Order order) {
        return -1;
    }

    @Override
    public boolean updateOrder(Order order) {
        return false;
    }

    @Override
    public Order getOrder(int id) {
        return null;
    }

    @Override
    public List<Order> getActiveOrdersByItem(int itemId, Order.OrderType type) {
        return Collections.emptyList();
    }

    @Override
    public long getLatestOrderCreatedAt(int itemId, Order.OrderType type) {
        return 0L;
    }

    @Override
    public List<Order> getOrdersByPlayer(String playerUuid) {
        return Collections.emptyList();
    }

    @Override
    public int insertTrade(Trade trade) {
        return -1;
    }

    @Override
    public boolean deleteTrade(int tradeId) {
        return false;
    }

    @Override
    public List<Trade> getTradesByPlayer(String playerUuid, int limit, int offset) {
        return Collections.emptyList();
    }

    @Override
    public List<Trade> getTradesByItem(int itemId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public long getTradeVolumeSince(int itemId, long since) {
        return 0L;
    }

    @Override
    public Trade getLastTrade(int itemId) {
        return null;
    }

    @Override
    public Trade getFirstTradeOfDate(int itemId, LocalDate date) {
        return null;
    }

    @Override
    public Trade getLastTradeOfDate(int itemId, LocalDate date) {
        return null;
    }

    @Override
    public boolean insertEscrow(EscrowEntry entry) {
        return false;
    }

    @Override
    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return null;
    }

    @Override
    public List<EscrowEntry> getEscrowsBySourceWarehouse(String warehouseId) {
        return Collections.emptyList();
    }

    @Override
    public boolean deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return false;
    }

    @Override
    public List<Order> getOrdersBySourceWarehouse(String warehouseId) {
        return Collections.emptyList();
    }

    @Override
    public void upsertItemStatus(ItemStatus status) {
    }

    @Override
    public ItemStatus getItemStatus(int itemId) {
        return null;
    }

    @Override
    public boolean addToWarehouse(String itemBase64, int quantity) {
        return false;
    }

    @Override
    public int getWarehouseQuantity(String itemBase64) {
        return 0;
    }

    @Override
    public boolean takeFromWarehouse(String itemBase64, int quantity) {
        return false;
    }

    @Override
    public Map<String, Integer> getWarehouseSnapshot() {
        return Collections.emptyMap();
    }

    @Override
    public boolean addToMoneyWarehouse(String playerUuid, BigDecimal amount) {
        return false;
    }

    @Override
    public BigDecimal getMoneyWarehouseBalance(String playerUuid) {
        return BigDecimal.ZERO;
    }

    @Override
    public boolean takeFromMoneyWarehouse(String playerUuid, BigDecimal amount) {
        return false;
    }

    @Override
    public boolean addToPlayerItemWarehouse(
        String playerUuid,
        String itemBase64,
        int quantity
    ) {
        return false;
    }

    @Override
    public Map<String, Integer> getPlayerItemWarehouse(String playerUuid) {
        return Collections.emptyMap();
    }

    @Override
    public boolean takeFromPlayerItemWarehouse(
        String playerUuid,
        String itemBase64,
        int quantity
    ) {
        return false;
    }

    @Override
    public int getDailyRegisterCount(String playerUuid, LocalDate date) {
        return 0;
    }

    @Override
    public void setDailyRegisterCount(
        String playerUuid,
        LocalDate date,
        int count
    ) {
    }

    @Override
    public void withdrawWarehouse(Player player) {
        this.notifyUnavailable(player);
    }

    @Override
    public void withdrawWarehouseMoney(Player player) {
        this.notifyUnavailable(player);
    }

    @Override
    public void withdrawWarehouseItem(Player player, String itemBase64) {
        this.notifyUnavailable(player);
    }

    private void notifyUnavailable(Player player) {
        if (player != null) {
            player.sendMessage("§c交易市场存储暂不可用，请稍后再试。");
        }
    }
}
