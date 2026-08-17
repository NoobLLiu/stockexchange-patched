/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 */
package com.github.exchange.storage;

import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

public interface StorageManager {
    public void init();

    public void shutdown();

    public int insertExchangeItem(ExchangeItem var1);

    public void updateExchangeItem(ExchangeItem var1);

    public void deleteExchangeItem(int var1);

    public ExchangeItem getExchangeItem(int var1);

    public ExchangeItem getExchangeItemByHash(String var1, String var2);

    public List<ExchangeItem> getAllExchangeItems();

    public int insertOrder(Order var1);

    public boolean updateOrder(Order var1);

    public Order getOrder(int var1);

    public List<Order> getActiveOrdersByItem(int var1, Order.OrderType var2);

    public long getLatestOrderCreatedAt(int var1, Order.OrderType var2);

    public List<Order> getOrdersByPlayer(String var1);

    public int insertTrade(Trade var1);

    public boolean deleteTrade(int var1);

    public List<Trade> getTradesByPlayer(String var1, int var2, int var3);

    public List<Trade> getTradesByItem(int var1, int var2);

    public long getTradeVolumeSince(int var1, long var2);

    public Trade getLastTrade(int var1);

    public Trade getFirstTradeOfDate(int var1, LocalDate var2);

    public Trade getLastTradeOfDate(int var1, LocalDate var2);

    public boolean insertEscrow(EscrowEntry var1);

    public EscrowEntry getEscrow(int var1, EscrowEntry.AssetType var2);

    public List<EscrowEntry> getEscrowsBySourceWarehouse(String var1);

    public boolean deleteEscrow(int var1, EscrowEntry.AssetType var2);

    public List<Order> getOrdersBySourceWarehouse(String var1);

    public void upsertItemStatus(ItemStatus var1);

    public ItemStatus getItemStatus(int var1);

    public boolean addToWarehouse(String var1, int var2);

    public int getWarehouseQuantity(String var1);

    public boolean takeFromWarehouse(String var1, int var2);

    public Map<String, Integer> getWarehouseSnapshot();

    public boolean addToMoneyWarehouse(String var1, BigDecimal var2);

    public BigDecimal getMoneyWarehouseBalance(String var1);

    public boolean takeFromMoneyWarehouse(String var1, BigDecimal var2);

    public boolean addToPlayerItemWarehouse(String var1, String var2, int var3);

    public Map<String, Integer> getPlayerItemWarehouse(String var1);

    public boolean takeFromPlayerItemWarehouse(String var1, String var2, int var3);

    public int getDailyRegisterCount(String var1, LocalDate var2);

    public void setDailyRegisterCount(String var1, LocalDate var2, int var3);

    public void withdrawWarehouse(Player var1);

    public void withdrawWarehouseMoney(Player var1);

    public void withdrawWarehouseItem(Player var1, String var2);
}
