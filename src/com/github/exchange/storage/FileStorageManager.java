/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.configuration.file.YamlConfiguration
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 */
package com.github.exchange.storage;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.storage.StorageManager;
import com.github.exchange.util.DurableFiles;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.InventoryDelivery;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.ItemSerializer;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class FileStorageManager
implements StorageManager {
    private static final String PLAYER_WAREHOUSE_PREFIX = "player:";
    private final StockExchangePlugin plugin;
    private final File dataFolder;
    private final File itemsFolder;
    private final File tradesFolder;
    private final File ordersFolder;
    private final File legacyWarehouseFolder;
    private final File warehouseStateFile;
    private final File dailyRegisterLimitFile;
    private final Map<Integer, ExchangeItem> itemCache = new ConcurrentHashMap<Integer, ExchangeItem>();
    private final Map<Integer, Order> orderCache = new ConcurrentHashMap<Integer, Order>();
    private final Map<Integer, Trade> tradeCache = new ConcurrentHashMap<Integer, Trade>();
    private final Map<String, EscrowEntry> escrowCache = new ConcurrentHashMap<String, EscrowEntry>();
    private final Map<Integer, ItemStatus> statusCache = new ConcurrentHashMap<Integer, ItemStatus>();
    private final Map<String, Integer> warehouseCache = new ConcurrentHashMap<String, Integer>();
    private final Map<String, BigDecimal> moneyWarehouseCache = new ConcurrentHashMap<String, BigDecimal>();
    private final Map<String, Map<String, Integer>> dailyRegisterLimitCache = new ConcurrentHashMap<String, Map<String, Integer>>();
    private final AtomicInteger nextItemId = new AtomicInteger(1);
    private final AtomicInteger nextOrderId = new AtomicInteger(1);
    private final AtomicInteger nextTradeId = new AtomicInteger(1);

    public FileStorageManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        this.itemsFolder = new File(this.dataFolder, "items");
        this.tradesFolder = new File(this.dataFolder, "trades");
        this.ordersFolder = new File(this.dataFolder, "orders");
        this.legacyWarehouseFolder = new File(this.dataFolder, "warehouse");
        this.warehouseStateFile = new File(
            this.dataFolder,
            "warehouse-state.yml"
        );
        this.dailyRegisterLimitFile = new File(this.dataFolder, "daily-register-limits.yml");
    }

    @Override
    public void init() {
        if (!this.dataFolder.exists() && !this.dataFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create storage data directory.");
        }
        if (!this.itemsFolder.exists() && !this.itemsFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create item storage directory.");
        }
        if (!this.tradesFolder.exists() && !this.tradesFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create trade storage directory.");
        }
        if (!this.ordersFolder.exists() && !this.ordersFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create order storage directory.");
        }
        this.loadIdCounters();
        this.loadAllItems();
        this.loadAllOrders();
        this.loadAllTrades();
        this.loadAllEscrow();
        this.loadAllStatuses();
        this.loadAllWarehouse();
        this.loadAllMoneyWarehouse();
        this.loadDailyRegisterLimits();
        this.plugin.getLogger().info("File storage initialized. Items: " + this.itemCache.size() + ", Orders: " + this.orderCache.size() + ", Trades: " + this.tradeCache.size());
    }

    @Override
    public void shutdown() {
        if (this.plugin.isStorageAvailable()) {
            this.saveIdCounters();
        }
    }

    private void loadIdCounters() {
        File file = new File(this.dataFolder, "ids.yml");
        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
            this.nextItemId.set(config.getInt("next_item_id", 1));
            this.nextOrderId.set(config.getInt("next_order_id", 1));
            this.nextTradeId.set(config.getInt("next_trade_id", 1));
        }
    }

    private void saveIdCounters() {
        File file = new File(this.dataFolder, "ids.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("next_item_id", (Object)this.nextItemId.get());
        config.set("next_order_id", (Object)this.nextOrderId.get());
        config.set("next_trade_id", (Object)this.nextTradeId.get());
        this.saveYamlAtomically(file, config, "ID counters");
    }

    private void loadAllItems() {
        File[] files = this.itemsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int maxId = 0;
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
            ExchangeItem item = new ExchangeItem(config.getInt("id"), config.getString("material"), config.getString("nbt_hash"), config.getString("item_base64"), config.getString("display_name"), null);
            item.setItemName(config.getString("item_name", item.getDisplayName()));
            item.setItemLore(config.getString("item_lore", ""));
            item.setCreatedByUuid(config.getString("created_by_uuid", ""));
            item.setCreatedByName(config.getString("created_by_name", ""));
            if (config.contains("created_at")) {
                item.setCreatedAt(new Timestamp(config.getLong("created_at")));
            }
            if (config.contains("last_stocked_at")) {
                item.setLastStockedAt(new Timestamp(config.getLong("last_stocked_at")));
            }
            if (config.contains("last_sell_catalog_activity_at")) {
                item.setLastSellCatalogActivityAt(
                    new Timestamp(config.getLong("last_sell_catalog_activity_at"))
                );
            }
            if (config.contains("last_empty_at")) {
                item.setLastEmptyAt(new Timestamp(config.getLong("last_empty_at")));
            }
            this.itemCache.put(item.getId(), item);
            if (item.getId() <= maxId) continue;
            maxId = item.getId();
        }
        if (maxId >= this.nextItemId.get()) {
            this.nextItemId.set(maxId + 1);
        }
    }

    @Override
    public int insertExchangeItem(ExchangeItem item) {
        int id = this.nextItemId.getAndIncrement();
        item.setId(id);
        this.itemCache.put(id, item);
        if (!this.saveExchangeItem(item)) {
            this.itemCache.remove(id, item);
            this.nextItemId.compareAndSet(id + 1, id);
            return -1;
        }
        this.saveIdCounters();
        return id;
    }

    private boolean saveExchangeItem(ExchangeItem item) {
        File file = new File(this.itemsFolder, item.getId() + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", (Object)item.getId());
        config.set("material", (Object)item.getMaterial());
        config.set("nbt_hash", (Object)item.getNbtHash());
        config.set("item_base64", (Object)item.getItemBase64());
        config.set("display_name", (Object)item.getDisplayName());
        config.set("item_name", (Object)(item.getItemName() != null ? item.getItemName() : item.getDisplayName()));
        config.set("item_lore", (Object)(item.getItemLore() != null ? item.getItemLore() : ""));
        config.set("created_by_uuid", (Object)(item.getCreatedByUuid() != null ? item.getCreatedByUuid() : ""));
        config.set("created_by_name", (Object)(item.getCreatedByName() != null ? item.getCreatedByName() : ""));
        config.set("created_at", (Object)(item.getCreatedAt() != null ? item.getCreatedAt().getTime() : System.currentTimeMillis()));
        config.set("last_stocked_at", (Object)(item.getLastStockedAt() != null ? item.getLastStockedAt().getTime() : 0L));
        config.set(
            "last_sell_catalog_activity_at",
            (Object)(item.getLastSellCatalogActivityAt() != null
                ? item.getLastSellCatalogActivityAt().getTime()
                : 0L)
        );
        config.set("last_empty_at", (Object)(item.getLastEmptyAt() != null ? item.getLastEmptyAt().getTime() : 0L));
        return this.saveYamlAtomically(
            file,
            config,
            "item " + item.getId()
        );
    }

    @Override
    public void updateExchangeItem(ExchangeItem item) {
        if (item == null || item.getId() <= 0) {
            return;
        }
        this.itemCache.put(item.getId(), item);
        this.saveExchangeItem(item);
    }

    @Override
    public synchronized void deleteExchangeItem(int itemId) {
        ExchangeItem removed = this.itemCache.remove(itemId);
        if (removed != null) {
            this.warehouseCache.remove(removed.getItemBase64());
            if (!this.saveWarehouse()) {
                this.failClosed(
                    "failed to persist warehouse cleanup for item " + itemId,
                    null
                );
            }
        }
        this.statusCache.remove(itemId);
        this.saveAllStatuses();
        List<Order> relatedOrders = new ArrayList<Order>();
        for (Order order : this.orderCache.values()) {
            if (order.getItemId() == itemId) {
                relatedOrders.add(order);
            }
        }
        for (Order order : relatedOrders) {
            this.orderCache.remove(order.getId());
            this.escrowCache.remove(this.escrowKey(order.getId(), EscrowEntry.AssetType.MONEY));
            this.escrowCache.remove(this.escrowKey(order.getId(), EscrowEntry.AssetType.ITEM));
        }
        List<Integer> relatedTradeIds = new ArrayList<Integer>();
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId) {
                continue;
            }
            relatedTradeIds.add(trade.getId());
        }
        for (Integer tradeId : relatedTradeIds) {
            this.tradeCache.remove(tradeId);
        }
        this.saveAllEscrow();
        if (!this.saveOrdersForItem(itemId)
            || !this.saveTradesForItem(itemId)) {
            this.failClosed(
                "failed to persist order or trade cleanup for item " + itemId,
                null
            );
        }
        File file = new File(this.itemsFolder, itemId + ".yml");
        if (!this.deleteFileDurably(file, "item " + itemId)) {
            this.failClosed(
                "failed to durably delete item " + itemId,
                null
            );
        }
    }

    @Override
    public ExchangeItem getExchangeItem(int id) {
        return this.itemCache.get(id);
    }

    @Override
    public ExchangeItem getExchangeItemByHash(String material, String nbtHash) {
        for (ExchangeItem item : this.itemCache.values()) {
            if (!item.getMaterial().equals(material) || !item.getNbtHash().equals(nbtHash)) continue;
            return item;
        }
        return null;
    }

    @Override
    public List<ExchangeItem> getAllExchangeItems() {
        ArrayList<ExchangeItem> items = new ArrayList<ExchangeItem>(this.itemCache.values());
        items.sort((a, b) -> {
            ItemStatus statusA = this.getItemStatus(a.getId());
            ItemStatus statusB = this.getItemStatus(b.getId());
            int volumeA = statusA != null ? statusA.getVolumeToday() : 0;
            int volumeB = statusB != null ? statusB.getVolumeToday() : 0;
            int cmp = Integer.compare(volumeB, volumeA);
            if (cmp != 0) {
                return cmp;
            }
            int stockA = this.warehouseCache.getOrDefault(a.getItemBase64(), 0);
            int stockB = this.warehouseCache.getOrDefault(b.getItemBase64(), 0);
            cmp = Integer.compare(stockB, stockA);
            if (cmp != 0) {
                return cmp;
            }
            Timestamp stockedA = a.getLastStockedAt();
            Timestamp stockedB = b.getLastStockedAt();
            long stockedAtA = stockedA != null ? stockedA.getTime() : 0L;
            long stockedAtB = stockedB != null ? stockedB.getTime() : 0L;
            cmp = Long.compare(stockedAtB, stockedAtA);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.getId(), b.getId());
        });
        return items;
    }

    private void loadAllOrders() {
        File[] files = this.ordersFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int maxId = 0;
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
            for (String key : config.getKeys(false)) {
                ConfigurationSection sec = config.getConfigurationSection(key);
                if (sec == null) continue;
                try {
                    Order order = new Order(sec.getInt("id"), Order.OrderType.valueOf(sec.getString("order_type")), sec.getInt("item_id"), sec.getString("player_uuid"), new BigDecimal(sec.getString("price")), sec.getInt("quantity"), sec.getInt("filled_qty"), Order.OrderStatus.valueOf(sec.getString("status")), new Timestamp(sec.getLong("created_at")), new Timestamp(sec.getLong("updated_at")));
                    order.setPlayerName(sec.getString("player_name", ""));
                    order.setSourceWarehouseId(sec.getString("source_warehouse_id"));
                    this.orderCache.put(order.getId(), order);
                    maxId = Math.max(maxId, order.getId());
                } catch (RuntimeException ex) {
                    this.warnBadRecord("order", file.getName(), key, ex);
                }
            }
        }
        if (maxId >= this.nextOrderId.get()) {
            this.nextOrderId.set(maxId + 1);
        }
    }

    @Override
    public int insertOrder(Order order) {
        int id = this.nextOrderId.getAndIncrement();
        order.setId(id);
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        }
        if (order.getUpdatedAt() == null) {
            order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        }
        this.orderCache.put(id, order);
        if (!this.saveOrdersForItem(order.getItemId())) {
            this.orderCache.remove(id);
            this.nextOrderId.compareAndSet(id + 1, id);
            return -1;
        }
        this.saveIdCounters();
        return id;
    }

    @Override
    public boolean updateOrder(Order order) {
        if (order == null || order.getId() <= 0) {
            return false;
        }
        Order previous = this.orderCache.get(order.getId());
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        this.orderCache.put(order.getId(), order);
        if (this.saveOrdersForItem(order.getItemId())) {
            return true;
        }
        if (previous == null) {
            this.orderCache.remove(order.getId());
        } else {
            this.orderCache.put(order.getId(), previous);
        }
        return false;
    }

    private boolean saveOrdersForItem(int itemId) {
        File file = new File(this.ordersFolder, "item_" + itemId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Order order : this.orderCache.values()) {
            if (order.getItemId() != itemId) continue;
            String key = String.valueOf(order.getId());
            config.set(key + ".id", (Object)order.getId());
            config.set(key + ".order_type", (Object)order.getOrderType().name());
            config.set(key + ".item_id", (Object)order.getItemId());
            config.set(key + ".player_uuid", (Object)order.getPlayerUuid());
            config.set(key + ".player_name", (Object)(order.getPlayerName() != null ? order.getPlayerName() : ""));
            config.set(key + ".source_warehouse_id", (Object)order.getSourceWarehouseId());
            config.set(key + ".price", (Object)order.getPrice().toString());
            config.set(key + ".quantity", (Object)order.getQuantity());
            config.set(key + ".filled_qty", (Object)order.getFilledQty());
            config.set(key + ".status", (Object)order.getStatus().name());
            config.set(key + ".created_at", (Object)order.getCreatedAt().getTime());
            config.set(key + ".updated_at", (Object)order.getUpdatedAt().getTime());
        }
        return this.saveYamlAtomically(
            file,
            config,
            "orders for item " + itemId
        );
    }

    @Override
    public Order getOrder(int id) {
        return this.orderCache.get(id);
    }

    @Override
    public List<Order> getActiveOrdersByItem(int itemId, Order.OrderType orderType) {
        ArrayList<Order> result = new ArrayList<Order>();
        for (Order order : this.orderCache.values()) {
            if (order.getItemId() != itemId || order.getOrderType() != orderType || !order.isActive()) continue;
            result.add(order);
        }
        if (orderType == Order.OrderType.BUY) {
            result.sort((a, b) -> {
                int cmp = b.getPrice().compareTo(a.getPrice());
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Long.compare(a.getCreatedAt().getTime(), b.getCreatedAt().getTime());
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a.getId(), b.getId());
            });
        } else {
            result.sort((a, b) -> {
                int cmp = a.getPrice().compareTo(b.getPrice());
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Long.compare(a.getCreatedAt().getTime(), b.getCreatedAt().getTime());
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a.getId(), b.getId());
            });
        }
        return result;
    }

    @Override
    public long getLatestOrderCreatedAt(int itemId, Order.OrderType orderType) {
        long latest = 0L;
        for (Order order : this.orderCache.values()) {
            if (order.getItemId() != itemId
                || order.getOrderType() != orderType
                || order.getCreatedAt() == null) {
                continue;
            }
            latest = Math.max(latest, order.getCreatedAt().getTime());
        }
        return latest;
    }

    @Override
    public List<Order> getOrdersByPlayer(String playerUuid) {
        ArrayList<Order> result = new ArrayList<Order>();
        for (Order order : this.orderCache.values()) {
            if (!order.getPlayerUuid().equals(playerUuid)) continue;
            result.add(order);
        }
        result.sort((a, b) -> Long.compare(b.getCreatedAt().getTime(), a.getCreatedAt().getTime()));
        return result;
    }

    @Override
    public List<Order> getOrdersBySourceWarehouse(String sourceWarehouseId) {
        ArrayList<Order> result = new ArrayList<Order>();
        if (sourceWarehouseId == null || sourceWarehouseId.isBlank()) {
            return result;
        }
        for (Order order : this.orderCache.values()) {
            if (order != null
                && sourceWarehouseId.equals(order.getSourceWarehouseId())) {
                result.add(order);
            }
        }
        result.sort(Comparator.comparingInt(Order::getId));
        return result;
    }

    private void loadAllTrades() {
        File[] files = this.tradesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int maxId = 0;
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
            for (String key : config.getKeys(false)) {
                ConfigurationSection sec = config.getConfigurationSection(key);
                if (sec == null) continue;
                try {
                    Trade trade = new Trade(sec.getInt("id"), sec.getInt("item_id"), sec.getString("buyer_uuid"), sec.getString("seller_uuid"), new BigDecimal(sec.getString("price")), sec.getInt("quantity"), new BigDecimal(sec.getString("total_amount")), new BigDecimal(sec.getString("buyer_fee")), new BigDecimal(sec.getString("seller_fee")), sec.getInt("buy_order_id"), sec.getInt("sell_order_id"), new Timestamp(sec.getLong("traded_at")));
                    this.tradeCache.put(trade.getId(), trade);
                    maxId = Math.max(maxId, trade.getId());
                } catch (RuntimeException ex) {
                    this.warnBadRecord("trade", file.getName(), key, ex);
                }
            }
        }
        if (maxId >= this.nextTradeId.get()) {
            this.nextTradeId.set(maxId + 1);
        }
    }

    @Override
    public int insertTrade(Trade trade) {
        int id = this.nextTradeId.getAndIncrement();
        trade.setId(id);
        if (trade.getTradedAt() == null) {
            trade.setTradedAt(new Timestamp(System.currentTimeMillis()));
        }
        this.tradeCache.put(id, trade);
        if (!this.saveTradesForItem(trade.getItemId())) {
            this.tradeCache.remove(id);
            this.nextTradeId.compareAndSet(id + 1, id);
            return -1;
        }
        this.saveIdCounters();
        return id;
    }

    @Override
    public boolean deleteTrade(int tradeId) {
        Trade removed = this.tradeCache.remove(tradeId);
        if (removed == null) {
            return true;
        }
        if (this.saveTradesForItem(removed.getItemId())) {
            return true;
        }
        this.tradeCache.put(tradeId, removed);
        return false;
    }

    private boolean saveTradesForItem(int itemId) {
        File file = new File(this.tradesFolder, "item_" + itemId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId) continue;
            String key = String.valueOf(trade.getId());
            config.set(key + ".id", (Object)trade.getId());
            config.set(key + ".item_id", (Object)trade.getItemId());
            config.set(key + ".buyer_uuid", (Object)trade.getBuyerUuid());
            config.set(key + ".seller_uuid", (Object)trade.getSellerUuid());
            config.set(key + ".price", (Object)trade.getPrice().toString());
            config.set(key + ".quantity", (Object)trade.getQuantity());
            config.set(key + ".total_amount", (Object)trade.getTotalAmount().toString());
            config.set(key + ".buyer_fee", (Object)trade.getBuyerFee().toString());
            config.set(key + ".seller_fee", (Object)trade.getSellerFee().toString());
            config.set(key + ".buy_order_id", (Object)trade.getBuyOrderId());
            config.set(key + ".sell_order_id", (Object)trade.getSellOrderId());
            config.set(key + ".traded_at", (Object)trade.getTradedAt().getTime());
        }
        return this.saveYamlAtomically(
            file,
            config,
            "trades for item " + itemId
        );
    }

    @Override
    public List<Trade> getTradesByPlayer(String playerUuid, int limit, int offset) {
        if (playerUuid == null || limit <= 0) {
            return new ArrayList<Trade>();
        }
        int safeOffset = Math.max(0, offset);
        ArrayList<Trade> result = new ArrayList<Trade>();
        for (Trade trade : this.tradeCache.values()) {
            if (!trade.getBuyerUuid().equals(playerUuid) && !trade.getSellerUuid().equals(playerUuid)) continue;
            result.add(trade);
        }
        result.sort((a, b) -> Long.compare(b.getTradedAt().getTime(), a.getTradedAt().getTime()));
        int fromIndex = Math.min(safeOffset, result.size());
        int toIndex = (int)Math.min((long)safeOffset + limit, result.size());
        if (fromIndex >= toIndex) {
            return new ArrayList<Trade>();
        }
        return result.subList(fromIndex, toIndex);
    }

    @Override
    public List<Trade> getTradesByItem(int itemId, int limit) {
        if (limit <= 0) {
            return new ArrayList<Trade>();
        }
        ArrayList<Trade> result = new ArrayList<Trade>();
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId) continue;
            result.add(trade);
        }
        result.sort((a, b) -> Long.compare(b.getTradedAt().getTime(), a.getTradedAt().getTime()));
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    @Override
    public long getTradeVolumeSince(int itemId, long sinceMillis) {
        long volume = 0L;
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId
                || trade.getTradedAt() == null
                || trade.getTradedAt().getTime() < sinceMillis) {
                continue;
            }
            volume += Math.max(0, trade.getQuantity());
        }
        return volume;
    }

    @Override
    public Trade getLastTrade(int itemId) {
        Trade last = null;
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId || last != null && !trade.getTradedAt().after(last.getTradedAt())) continue;
            last = trade;
        }
        return last;
    }

    @Override
    public Trade getFirstTradeOfDate(int itemId, LocalDate date) {
        if (date == null) {
            return null;
        }
        Trade first = null;
        ZoneId zone = ZoneId.systemDefault();
        for (Trade trade : this.tradeCache.values()) {
            LocalDate tradeDate;
            if (trade.getItemId() != itemId || trade.getTradedAt() == null || !date.equals(tradeDate = Instant.ofEpochMilli(trade.getTradedAt().getTime()).atZone(zone).toLocalDate()) || first != null && !trade.getTradedAt().before(first.getTradedAt())) continue;
            first = trade;
        }
        return first;
    }

    @Override
    public Trade getLastTradeOfDate(int itemId, LocalDate date) {
        if (date == null) {
            return null;
        }
        Trade last = null;
        ZoneId zone = ZoneId.systemDefault();
        for (Trade trade : this.tradeCache.values()) {
            LocalDate tradeDate;
            if (trade.getItemId() != itemId || trade.getTradedAt() == null || !date.equals(tradeDate = Instant.ofEpochMilli(trade.getTradedAt().getTime()).atZone(zone).toLocalDate()) || last != null && !trade.getTradedAt().after(last.getTradedAt())) continue;
            last = trade;
        }
        return last;
    }

    private void loadAllEscrow() {
        File file = new File(this.dataFolder, "escrow.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
        for (String key : config.getKeys(false)) {
            ConfigurationSection sec = config.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                EscrowEntry entry = new EscrowEntry(sec.getInt("order_id"), sec.getString("player_uuid"), EscrowEntry.AssetType.valueOf(sec.getString("asset_type")), sec.contains("amount") ? new BigDecimal(sec.getString("amount")) : BigDecimal.ZERO, sec.getString("item_base64"), sec.getInt("quantity"));
                entry.setSourceWarehouseId(sec.getString("source_warehouse_id"));
                this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
            } catch (RuntimeException ex) {
                this.warnBadRecord("escrow", file.getName(), key, ex);
            }
        }
    }

    private String escrowKey(int orderId, EscrowEntry.AssetType type) {
        return orderId + "_" + type.name();
    }

    @Override
    public boolean insertEscrow(EscrowEntry entry) {
        if (entry == null || entry.getOrderId() <= 0 || entry.getAssetType() == null) {
            return false;
        }
        String key = this.escrowKey(entry.getOrderId(), entry.getAssetType());
        EscrowEntry previous = this.escrowCache.put(key, entry);
        if (this.saveAllEscrow()) {
            return true;
        }
        if (previous == null) {
            this.escrowCache.remove(key);
        } else {
            this.escrowCache.put(key, previous);
        }
        return false;
    }

    @Override
    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return this.escrowCache.get(this.escrowKey(orderId, assetType));
    }

    @Override
    public List<EscrowEntry> getEscrowsBySourceWarehouse(String sourceWarehouseId) {
        ArrayList<EscrowEntry> result = new ArrayList<EscrowEntry>();
        if (sourceWarehouseId == null || sourceWarehouseId.isBlank()) {
            return result;
        }
        for (EscrowEntry entry : this.escrowCache.values()) {
            if (entry != null
                && sourceWarehouseId.equals(entry.getSourceWarehouseId())) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparingInt(EscrowEntry::getOrderId)
            .thenComparing(entry -> entry.getAssetType().name()));
        return result;
    }

    @Override
    public boolean deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        if (assetType == null) {
            return false;
        }
        String key = this.escrowKey(orderId, assetType);
        EscrowEntry removed = this.escrowCache.remove(key);
        if (this.saveAllEscrow()) {
            return true;
        }
        if (removed != null) {
            this.escrowCache.put(key, removed);
        }
        return false;
    }

    private boolean saveAllEscrow() {
        File file = new File(this.dataFolder, "escrow.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (EscrowEntry entry : this.escrowCache.values()) {
            String key = this.escrowKey(entry.getOrderId(), entry.getAssetType());
            config.set(key + ".order_id", (Object)entry.getOrderId());
            config.set(key + ".player_uuid", (Object)entry.getPlayerUuid());
            config.set(key + ".asset_type", (Object)entry.getAssetType().name());
            config.set(key + ".source_warehouse_id", (Object)entry.getSourceWarehouseId());
            config.set(key + ".amount", (Object)(entry.getAmount() != null ? entry.getAmount().toString() : "0"));
            config.set(key + ".item_base64", (Object)entry.getItemBase64());
            config.set(key + ".quantity", (Object)entry.getQuantity());
        }
        return this.saveYamlAtomically(file, config, "escrow");
    }

    private void loadAllStatuses() {
        File file = new File(this.dataFolder, "status.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
        for (String key : config.getKeys(false)) {
            ConfigurationSection sec = config.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                ItemStatus status = new ItemStatus(sec.getInt("item_id"), sec.getBoolean("is_suspended"), new BigDecimal(sec.getString("last_close")), new BigDecimal(sec.getString("last_open")), new BigDecimal(sec.getString("high_today")), new BigDecimal(sec.getString("low_today")), sec.getInt("volume_today"));
                status.setLowestSellCurrent(new BigDecimal(sec.getString("lowest_sell_current", "0")));
                status.setLowestSellReference(new BigDecimal(sec.getString("lowest_sell_reference", "0")));
                status.setLowestSellReferenceAt(sec.getLong("lowest_sell_reference_at", 0L));
                status.setLowestSellReference7d(new BigDecimal(sec.getString("lowest_sell_reference_7d", sec.getString("lowest_sell_reference", "0"))));
                status.setLowestSellReferenceAt7d(sec.getLong("lowest_sell_reference_at_7d", sec.getLong("lowest_sell_reference_at", 0L)));
                status.setLowestSellReference30d(new BigDecimal(sec.getString("lowest_sell_reference_30d", sec.getString("lowest_sell_reference", "0"))));
                status.setLowestSellReferenceAt30d(sec.getLong("lowest_sell_reference_at_30d", sec.getLong("lowest_sell_reference_at", 0L)));
                this.statusCache.put(status.getItemId(), status);
            } catch (RuntimeException ex) {
                this.warnBadRecord("status", file.getName(), key, ex);
            }
        }
    }

    @Override
    public void upsertItemStatus(ItemStatus status) {
        this.statusCache.put(status.getItemId(), status);
        this.saveAllStatuses();
    }

    @Override
    public ItemStatus getItemStatus(int itemId) {
        ItemStatus status = this.statusCache.get(itemId);
        if (status == null) {
            return null;
        }
        this.refreshDailyStatus(status);
        return status;
    }

    private void saveAllStatuses() {
        File file = new File(this.dataFolder, "status.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (ItemStatus status : this.statusCache.values()) {
            String key = String.valueOf(status.getItemId());
            config.set(key + ".item_id", (Object)status.getItemId());
            config.set(key + ".is_suspended", (Object)status.isSuspended());
            config.set(key + ".last_close", (Object)(status.getLastClose() != null ? status.getLastClose().toString() : "0"));
            config.set(key + ".last_open", (Object)(status.getLastOpen() != null ? status.getLastOpen().toString() : "0"));
            config.set(key + ".high_today", (Object)(status.getHighToday() != null ? status.getHighToday().toString() : "0"));
            config.set(key + ".low_today", (Object)(status.getLowToday() != null ? status.getLowToday().toString() : "0"));
            config.set(key + ".volume_today", (Object)status.getVolumeToday());
            config.set(key + ".lowest_sell_current", (Object)(status.getLowestSellCurrent() != null ? status.getLowestSellCurrent().toString() : "0"));
            config.set(key + ".lowest_sell_reference", (Object)(status.getLowestSellReference() != null ? status.getLowestSellReference().toString() : "0"));
            config.set(key + ".lowest_sell_reference_at", (Object)status.getLowestSellReferenceAt());
            config.set(key + ".lowest_sell_reference_7d", (Object)(status.getLowestSellReference7d() != null ? status.getLowestSellReference7d().toString() : "0"));
            config.set(key + ".lowest_sell_reference_at_7d", (Object)status.getLowestSellReferenceAt7d());
            config.set(key + ".lowest_sell_reference_30d", (Object)(status.getLowestSellReference30d() != null ? status.getLowestSellReference30d().toString() : "0"));
            config.set(key + ".lowest_sell_reference_at_30d", (Object)status.getLowestSellReferenceAt30d());
        }
        this.saveYamlAtomically(file, config, "statuses");
    }

    private void loadAllWarehouse() {
        this.warehouseCache.clear();
        if (this.warehouseStateFile.exists()) {
            this.loadWarehouseStateFile();
            this.archiveLegacyWarehouseDirectory();
            return;
        }

        this.recoverLegacyWarehouseDirectoryIfNeeded();
        this.loadLegacyWarehouseDirectory();
        if (!this.saveWarehouse()) {
            throw new IllegalStateException(
                "Failed to persist the authoritative warehouse state."
            );
        }
        if (this.legacyWarehouseFolder.exists()) {
            this.plugin.getLogger().info(
                "Migrated legacy warehouse directory to "
                    + this.warehouseStateFile.getName()
                    + "; the legacy directory is now ignored."
            );
            this.archiveLegacyWarehouseDirectory();
        }
    }

    private void loadWarehouseStateFile() {
        YamlConfiguration config = this.loadYamlStrictly(
            this.warehouseStateFile,
            "warehouse state"
        );
        int version = config.getInt("version", -1);
        if (version != FileWarehouseState.VERSION) {
            throw new IllegalStateException(
                "Unsupported warehouse state version: " + version
            );
        }
        try {
            this.warehouseCache.putAll(
                FileWarehouseState.decode(config.get("entries"))
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Invalid warehouse state in "
                    + this.warehouseStateFile.getName(),
                exception
            );
        }
    }

    private void loadLegacyWarehouseDirectory() {
        if (!this.legacyWarehouseFolder.exists()) {
            return;
        }
        if (!this.legacyWarehouseFolder.isDirectory()) {
            throw new IllegalStateException(
                "Legacy warehouse path is not a directory."
            );
        }
        File[] files = this.legacyWarehouseFolder.listFiles(
            (directory, name) -> name.endsWith(".yml")
        );
        if (files == null) {
            throw new IllegalStateException(
                "Failed to list the legacy warehouse directory."
            );
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            YamlConfiguration config = this.loadYamlStrictly(
                file,
                "legacy warehouse entry " + file.getName()
            );
            Map<String, Object> rawEntry = new HashMap<String, Object>();
            rawEntry.put("item_base64", config.get("item_base64"));
            rawEntry.put("quantity", config.get("quantity"));
            Map<String, Integer> decoded;
            try {
                decoded = FileWarehouseState.decode(List.of(rawEntry));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "Invalid legacy warehouse entry " + file.getName(),
                    exception
                );
            }
            Map.Entry<String, Integer> entry =
                decoded.entrySet().iterator().next();
            String itemBase64 = entry.getKey();
            int previous = this.warehouseCache.getOrDefault(itemBase64, 0);
            int combined;
            try {
                combined = Math.addExact(previous, entry.getValue());
            } catch (ArithmeticException exception) {
                throw new IllegalStateException(
                    "Legacy warehouse quantity overflow for "
                        + itemBase64,
                    exception
                );
            }
            this.warehouseCache.put(itemBase64, combined);
        }
    }

    private YamlConfiguration loadYamlStrictly(
        File file,
        String description
    ) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
            return config;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(
                "Failed to load " + description + ".",
                exception
            );
        }
    }

    @Override
    public synchronized boolean addToWarehouse(String itemBase64, int quantity) {
        if (!this.plugin.isStorageAvailable()
            || itemBase64 == null || quantity <= 0) {
            return false;
        }
        int previous = this.warehouseCache.getOrDefault(itemBase64, 0);
        int updated;
        try {
            updated = Math.addExact(previous, quantity);
        } catch (ArithmeticException exception) {
            return false;
        }
        this.warehouseCache.put(itemBase64, updated);
        if (this.saveWarehouse()) {
            return true;
        }
        if (previous <= 0) {
            this.warehouseCache.remove(itemBase64);
        } else {
            this.warehouseCache.put(itemBase64, previous);
        }
        return false;
    }

    @Override
    public synchronized int getWarehouseQuantity(String itemBase64) {
        return this.warehouseCache.getOrDefault(itemBase64, 0);
    }

    @Override
    public synchronized boolean takeFromWarehouse(String itemBase64, int quantity) {
        if (!this.plugin.isStorageAvailable()
            || itemBase64 == null || quantity <= 0) {
            return false;
        }
        int current = this.warehouseCache.getOrDefault(itemBase64, 0);
        if (current < quantity) {
            return false;
        }
        if (current == quantity) {
            this.warehouseCache.remove(itemBase64);
        } else {
            this.warehouseCache.put(itemBase64, current - quantity);
        }
        if (this.saveWarehouse()) {
            this.markItemEmptyState(itemBase64);
            return true;
        }
        this.warehouseCache.put(itemBase64, current);
        return false;
    }

    @Override
    public synchronized Map<String, Integer> getWarehouseSnapshot() {
        HashMap<String, Integer> snapshot = new HashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
            if (entry.getKey().startsWith(PLAYER_WAREHOUSE_PREFIX) || entry.getValue() <= 0) {
                continue;
            }
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return snapshot;
    }

    private synchronized boolean saveWarehouse() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("version", FileWarehouseState.VERSION);
        try {
            config.set(
                "entries",
                FileWarehouseState.encode(
                    new HashMap<String, Integer>(this.warehouseCache)
                )
            );
        } catch (IllegalArgumentException exception) {
            this.plugin.getLogger().severe(
                "Refusing to save invalid warehouse state: "
                    + exception.getMessage()
            );
            return false;
        }
        return this.saveYamlAtomically(
            this.warehouseStateFile,
            config,
            "warehouse state"
        );
    }

    private void markItemEmptyState(String itemBase64) {
        ExchangeItem item = this.findItemByBase64(itemBase64);
        if (item == null) {
            return;
        }
        int remaining = this.warehouseCache.getOrDefault(itemBase64, 0);
        if (remaining <= 0) {
            if (item.getLastEmptyAt() == null) {
                item.setLastEmptyAt(new Timestamp(System.currentTimeMillis()));
            }
        } else {
            item.setLastEmptyAt(null);
        }
        this.saveExchangeItem(item);
    }

    private ExchangeItem findItemByBase64(String itemBase64) {
        for (ExchangeItem item : this.itemCache.values()) {
            if (itemBase64.equals(item.getItemBase64())) {
                return item;
            }
        }
        return null;
    }

    private void refreshDailyStatus(ItemStatus status) {
        if (status == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        Trade firstToday = null;
        Trade lastOverall = null;
        BigDecimal highToday = BigDecimal.ZERO;
        BigDecimal lowToday = BigDecimal.ZERO;
        int volumeToday = 0;
        ZoneId zone = ZoneId.systemDefault();
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != status.getItemId() || trade.getTradedAt() == null || trade.getPrice() == null) {
                continue;
            }
            if (lastOverall == null || trade.getTradedAt().after(lastOverall.getTradedAt())) {
                lastOverall = trade;
            }
            LocalDate tradeDate = Instant.ofEpochMilli(trade.getTradedAt().getTime()).atZone(zone).toLocalDate();
            if (!today.equals(tradeDate)) {
                continue;
            }
            if (firstToday == null || trade.getTradedAt().before(firstToday.getTradedAt())) {
                firstToday = trade;
            }
            volumeToday += Math.max(0, trade.getQuantity());
            if (highToday.compareTo(BigDecimal.ZERO) == 0 || trade.getPrice().compareTo(highToday) > 0) {
                highToday = trade.getPrice();
            }
            if (lowToday.compareTo(BigDecimal.ZERO) == 0 || trade.getPrice().compareTo(lowToday) < 0) {
                lowToday = trade.getPrice();
            }
        }
        BigDecimal currentLastClose = status.getLastClose() != null ? status.getLastClose() : BigDecimal.ZERO;
        BigDecimal nextLastClose = lastOverall != null ? lastOverall.getPrice() : currentLastClose;
        BigDecimal nextLastOpen = firstToday != null ? firstToday.getPrice() : BigDecimal.ZERO;
        BigDecimal nextHighToday = firstToday != null ? highToday : BigDecimal.ZERO;
        BigDecimal nextLowToday = firstToday != null ? lowToday : BigDecimal.ZERO;
        if (currentLastClose.compareTo(nextLastClose) == 0
            && compareNullable(status.getLastOpen(), nextLastOpen) == 0
            && compareNullable(status.getHighToday(), nextHighToday) == 0
            && compareNullable(status.getLowToday(), nextLowToday) == 0
            && status.getVolumeToday() == volumeToday) {
            return;
        }
        status.setLastClose(nextLastClose);
        status.setLastOpen(nextLastOpen);
        status.setHighToday(nextHighToday);
        status.setLowToday(nextLowToday);
        status.setVolumeToday(volumeToday);
        this.statusCache.put(status.getItemId(), status);
        this.saveAllStatuses();
    }

    private int compareNullable(BigDecimal left, BigDecimal right) {
        BigDecimal safeLeft = left != null ? left : BigDecimal.ZERO;
        BigDecimal safeRight = right != null ? right : BigDecimal.ZERO;
        return safeLeft.compareTo(safeRight);
    }

    private void loadDailyRegisterLimits() {
        this.dailyRegisterLimitCache.clear();
        if (!this.dailyRegisterLimitFile.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(this.dailyRegisterLimitFile);
        for (String playerUuid : config.getKeys(false)) {
            ConfigurationSection section = config.getConfigurationSection(playerUuid);
            if (section == null) {
                continue;
            }
            Map<String, Integer> counts = new ConcurrentHashMap<String, Integer>();
            for (String dateKey : section.getKeys(false)) {
                counts.put(dateKey, section.getInt(dateKey, 0));
            }
            this.dailyRegisterLimitCache.put(playerUuid, counts);
        }
    }

    private boolean saveDailyRegisterLimits() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Integer>> playerEntry : this.dailyRegisterLimitCache.entrySet()) {
            for (Map.Entry<String, Integer> dayEntry : playerEntry.getValue().entrySet()) {
                config.set(playerEntry.getKey() + "." + dayEntry.getKey(), dayEntry.getValue());
            }
        }
        return this.saveYamlAtomically(this.dailyRegisterLimitFile, config, "daily register limits");
    }

    @Override
    public int getDailyRegisterCount(String playerUuid, LocalDate date) {
        if (playerUuid == null || date == null) {
            return 0;
        }
        Map<String, Integer> counts = this.dailyRegisterLimitCache.get(playerUuid);
        if (counts == null) {
            return 0;
        }
        return counts.getOrDefault(date.toString(), 0);
    }

    @Override
    public synchronized void setDailyRegisterCount(String playerUuid, LocalDate date, int count) {
        if (playerUuid == null || date == null || count < 0) {
            return;
        }
        Map<String, Integer> previousCounts = this.dailyRegisterLimitCache.get(playerUuid);
        Map<String, Integer> snapshot = previousCounts == null
            ? null : new ConcurrentHashMap<String, Integer>(previousCounts);
        Map<String, Integer> counts = previousCounts != null
            ? previousCounts
            : this.dailyRegisterLimitCache.computeIfAbsent(
                playerUuid, ignored -> new ConcurrentHashMap<String, Integer>());
        String dateKey = date.toString();
        if (count <= 0) {
            counts.remove(dateKey);
            if (counts.isEmpty()) {
                this.dailyRegisterLimitCache.remove(playerUuid);
            }
        } else {
            counts.put(dateKey, count);
        }
        if (this.saveDailyRegisterLimits()) {
            return;
        }
        if (snapshot == null || snapshot.isEmpty()) {
            this.dailyRegisterLimitCache.remove(playerUuid);
        } else {
            this.dailyRegisterLimitCache.put(playerUuid, snapshot);
        }
    }

    private void loadAllMoneyWarehouse() {
        File file = new File(this.dataFolder, "moneywarehouse.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
        for (String key : config.getKeys(false)) {
            ConfigurationSection sec = config.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                String playerUuid = sec.getString("player_uuid");
                BigDecimal amount = new BigDecimal(sec.getString("amount", "0"));
                if (playerUuid == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                this.moneyWarehouseCache.put(playerUuid, this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO).add(amount));
            } catch (RuntimeException ex) {
                this.warnBadRecord("money warehouse", file.getName(), key, ex);
            }
        }
    }

    private void warnBadRecord(String type, String source, String key, RuntimeException exception) {
        this.plugin.getLogger().warning("Skipping invalid " + type + " record " + source + "#" + key
            + ": " + exception.getMessage());
    }

    @Override
    public synchronized boolean addToMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (!this.plugin.isStorageAvailable()
            || playerUuid == null || amount == null
            || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal previous = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        this.moneyWarehouseCache.put(playerUuid, previous.add(amount));
        if (this.saveMoneyWarehouse()) {
            return true;
        }
        if (previous.compareTo(BigDecimal.ZERO) <= 0) {
            this.moneyWarehouseCache.remove(playerUuid);
        } else {
            this.moneyWarehouseCache.put(playerUuid, previous);
        }
        return false;
    }

    @Override
    public synchronized BigDecimal getMoneyWarehouseBalance(String playerUuid) {
        if (!this.plugin.isStorageAvailable() || playerUuid == null) {
            return BigDecimal.ZERO;
        }
        return this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
    }

    @Override
    public synchronized boolean takeFromMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (!this.plugin.isStorageAvailable()
            || playerUuid == null || amount == null
            || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal current = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        if (current.compareTo(amount) < 0) {
            return false;
        }
        BigDecimal newBalance = current.subtract(amount);
        if (newBalance.compareTo(BigDecimal.ZERO) <= 0) {
            this.moneyWarehouseCache.remove(playerUuid);
        } else {
            this.moneyWarehouseCache.put(playerUuid, newBalance);
        }
        if (this.saveMoneyWarehouse()) {
            return true;
        }
        this.moneyWarehouseCache.put(playerUuid, current);
        return false;
    }

    private String getPlayerWarehouseKey(String playerUuid, String itemBase64) {
        return PLAYER_WAREHOUSE_PREFIX + playerUuid + "|" + itemBase64;
    }

    @Override
    public synchronized boolean addToPlayerItemWarehouse(String playerUuid, String itemBase64, int quantity) {
        if (!this.plugin.isStorageAvailable()
            || playerUuid == null || itemBase64 == null || quantity <= 0) {
            return false;
        }
        String key = this.getPlayerWarehouseKey(playerUuid, itemBase64);
        int previous = this.warehouseCache.getOrDefault(key, 0);
        int updated;
        try {
            updated = Math.addExact(previous, quantity);
        } catch (ArithmeticException exception) {
            return false;
        }
        this.warehouseCache.put(key, updated);
        if (this.saveWarehouse()) {
            return true;
        }
        if (previous <= 0) {
            this.warehouseCache.remove(key);
        } else {
            this.warehouseCache.put(key, previous);
        }
        return false;
    }

    @Override
    public synchronized Map<String, Integer> getPlayerItemWarehouse(String playerUuid) {
        HashMap<String, Integer> snapshot = new HashMap<String, Integer>();
        if (!this.plugin.isStorageAvailable()
            || playerUuid == null || playerUuid.isEmpty()) {
            return snapshot;
        }
        String prefix = PLAYER_WAREHOUSE_PREFIX + playerUuid + "|";
        for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getValue() <= 0) {
                continue;
            }
            snapshot.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
        return snapshot;
    }

    @Override
    public synchronized boolean takeFromPlayerItemWarehouse(String playerUuid, String itemBase64, int quantity) {
        if (!this.plugin.isStorageAvailable()
            || playerUuid == null || itemBase64 == null || quantity <= 0) {
            return false;
        }
        String key = this.getPlayerWarehouseKey(playerUuid, itemBase64);
        int current = this.warehouseCache.getOrDefault(key, 0);
        if (current < quantity) {
            return false;
        }
        if (current == quantity) {
            this.warehouseCache.remove(key);
        } else {
            this.warehouseCache.put(key, current - quantity);
        }
        if (this.saveWarehouse()) {
            return true;
        }
        this.warehouseCache.put(key, current);
        return false;
    }

    private boolean saveMoneyWarehouse() {
        if (!this.plugin.isStorageAvailable()) {
            return false;
        }
        File file = new File(this.dataFolder, "moneywarehouse.yml");
        YamlConfiguration config = new YamlConfiguration();
        int index = 0;
        for (Map.Entry<String, BigDecimal> entry : this.moneyWarehouseCache.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
            String key = "entry_" + index;
            config.set(key + ".player_uuid", (Object)entry.getKey());
            config.set(key + ".amount", (Object)entry.getValue().toString());
            ++index;
        }
        return this.saveYamlAtomically(file, config, "money warehouse");
    }

    @Override
    public void withdrawWarehouse(Player player) {
        if (player == null) {
            return;
        }
        if (this.isAssetDeliveryBlocked()) {
            this.sendAssetDeliveryBlocked(player);
            return;
        }
        String playerUuid = player.getUniqueId().toString();
        BigDecimal moneyBalance = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        boolean withdrewMoney = false;
        if (moneyBalance.compareTo(BigDecimal.ZERO) > 0
            && this.takeFromMoneyWarehouse(playerUuid, moneyBalance)) {
            if (EconomyUtil.deposit(player.getUniqueId(), moneyBalance)) {
                withdrewMoney = true;
                player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + moneyBalance + " \u00a7a\u661f\u5149\u70b9\u3002");
            } else if (!this.addToMoneyWarehouse(playerUuid, moneyBalance)) {
                this.plugin.getLogger().severe("[AssetAudit] MONEY_WITHDRAW_ROLLBACK_FAILED player=" + playerUuid
                    + " amount=" + moneyBalance);
                player.sendMessage("\u00a7c\u661f\u5149\u70b9\u63d0\u53d6\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
            } else {
                player.sendMessage("\u00a7c\u5f53\u524d\u65e0\u6cd5\u53d1\u653e\u661f\u5149\u70b9\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            }
        } else if (moneyBalance.compareTo(BigDecimal.ZERO) > 0) {
            player.sendMessage("\u00a7c\u661f\u5149\u70b9\u4ed3\u5e93\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
        }

        int totalItems = 0;
        for (Map.Entry<String, Integer> entry : this.getPlayerItemWarehouse(playerUuid).entrySet()) {
            ItemStack itemStack = ItemSerializer.itemFromBase64(entry.getKey());
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (itemStack == null || quantity <= 0 || !this.takeFromPlayerItemWarehouse(playerUuid, entry.getKey(), quantity)) {
                continue;
            }
            try {
                int added = InventoryDelivery.addUpTo(player, itemStack, quantity);
                int remaining = quantity - added;
                if (remaining > 0 && !this.addToPlayerItemWarehouse(playerUuid, entry.getKey(), remaining)) {
                    this.plugin.getLogger().severe("[AssetAudit] ITEM_WITHDRAW_RESTORE_FAILED player=" + playerUuid
                        + " item=" + entry.getKey() + " quantity=" + remaining);
                }
                totalItems += added;
            }
            catch (Throwable throwable) {
                if (!this.addToPlayerItemWarehouse(playerUuid, entry.getKey(), quantity)) {
                    this.plugin.getLogger().severe("[AssetAudit] ITEM_WITHDRAW_ROLLBACK_FAILED player=" + playerUuid
                        + " item=" + entry.getKey() + " quantity=" + quantity);
                }
            }
        }
        this.saveWarehouse();
        if (totalItems > 0) {
            player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + totalItems + " \u00a7a\u4e2a\u7269\u54c1\u3002");
        }
        if (!withdrewMoney && totalItems <= 0) {
            player.sendMessage("\u00a77\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u63d0\u53d6\u7684\u7269\u54c1\u6216\u661f\u5149\u70b9\u3002");
        }
    }

    @Override
    public void withdrawWarehouseMoney(Player player) {
        if (player == null) {
            return;
        }
        if (this.isAssetDeliveryBlocked()) {
            this.sendAssetDeliveryBlocked(player);
            return;
        }
        String playerUuid = player.getUniqueId().toString();
        BigDecimal moneyBalance = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        if (moneyBalance.compareTo(BigDecimal.ZERO) <= 0) {
            player.sendMessage("\u00a77\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u63d0\u53d6\u7684\u661f\u5149\u70b9\u3002");
            return;
        }
        if (!this.takeFromMoneyWarehouse(playerUuid, moneyBalance)) {
            player.sendMessage("\u00a7c\u661f\u5149\u70b9\u4ed3\u5e93\u8bfb\u53d6\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            return;
        }
        if (!EconomyUtil.deposit(player.getUniqueId(), moneyBalance)) {
            if (!this.addToMoneyWarehouse(playerUuid, moneyBalance)) {
                this.plugin.getLogger().severe("[AssetAudit] MONEY_WITHDRAW_ROLLBACK_FAILED player=" + playerUuid
                    + " amount=" + moneyBalance);
                player.sendMessage("\u00a7c\u661f\u5149\u70b9\u63d0\u53d6\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
            } else {
                player.sendMessage("\u00a7c\u5f53\u524d\u65e0\u6cd5\u53d1\u653e\u661f\u5149\u70b9\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            }
            return;
        }
        player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + moneyBalance + " \u00a7a\u661f\u5149\u70b9\u3002");
    }

    @Override
    public void withdrawWarehouseItem(Player player, String itemBase64) {
        if (player == null || itemBase64 == null || itemBase64.isEmpty()) {
            if (player != null) {
                player.sendMessage("\u00a7c\u65e0\u6548\u7684\u4ed3\u5e93\u7269\u54c1\u3002");
            }
            return;
        }
        if (this.isAssetDeliveryBlocked()) {
            this.sendAssetDeliveryBlocked(player);
            return;
        }
        String playerUuid = player.getUniqueId().toString();
        int quantity = this.getPlayerItemWarehouse(playerUuid).getOrDefault(itemBase64, 0);
        if (quantity <= 0) {
            player.sendMessage("\u00a77\u8be5\u7269\u54c1\u5df2\u88ab\u63d0\u53d6\u6216\u4e0d\u5b58\u5728\u3002");
            return;
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(itemBase64);
        if (itemStack == null) {
            player.sendMessage("\u00a7c\u7269\u54c1\u6570\u636e\u635f\u574f\uff0c\u65e0\u6cd5\u63d0\u53d6\u3002");
            return;
        }
        if (!this.takeFromPlayerItemWarehouse(playerUuid, itemBase64, quantity)) {
            player.sendMessage("\u00a7c\u4ed3\u5e93\u6570\u636e\u53d8\u66f4\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            return;
        }
        try {
            int added = InventoryDelivery.addUpTo(player, itemStack, quantity);
            int remaining = quantity - added;
            if (remaining > 0 && !this.addToPlayerItemWarehouse(playerUuid, itemBase64, remaining)) {
                this.plugin.getLogger().severe("[AssetAudit] ITEM_WITHDRAW_RESTORE_FAILED player=" + playerUuid
                    + " item=" + itemBase64 + " quantity=" + remaining);
                player.sendMessage("\u00a7c\u672a\u80fd\u56de\u5b58\u5269\u4f59\u7269\u54c1\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
            }
            player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + added + " \u00a7a\u4e2a\u7269\u54c1"
                + (remaining > 0 ? "\uff0c\u5269\u4f59 " + remaining + " \u4e2a\u4fdd\u7559\u5728\u4ed3\u5e93\u3002" : "\u3002"));
        }
        catch (Throwable throwable) {
            if (!this.addToPlayerItemWarehouse(playerUuid, itemBase64, quantity)) {
                this.plugin.getLogger().severe("[AssetAudit] ITEM_WITHDRAW_ROLLBACK_FAILED player=" + playerUuid
                    + " item=" + itemBase64 + " quantity=" + quantity);
                player.sendMessage("\u00a7c\u7269\u54c1\u63d0\u53d6\u53d1\u751f\u5f02\u5e38\uff0c\u4e14\u65e0\u6cd5\u81ea\u52a8\u56de\u5b58\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
            } else {
                player.sendMessage("\u00a7c\u7269\u54c1\u63d0\u53d6\u5931\u8d25\uff0c\u5df2\u8fd4\u56de\u4ed3\u5e93\u3002");
            }
        }
    }

    private boolean isAssetDeliveryBlocked() {
        return !this.plugin.isStorageAvailable()
            || this.plugin.isSettlementDeliveryBlocked();
    }

    private void sendAssetDeliveryBlocked(Player player) {
        if (player != null) {
            player.sendMessage(
                this.plugin.isStorageAvailable()
                    ? "\u00a7c\u4ea4\u6613\u7ed3\u7b97\u6b63\u5728\u6838\u9a8c\uff0c\u4ed3\u5e93\u8d44\u4ea7\u6682\u4e0d\u53ef\u63d0\u53d6\u3002"
                    : "\u00a7c\u4ea4\u6613\u5e02\u573a\u5b58\u50a8\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002"
            );
        }
    }

    private boolean saveYamlAtomically(File target, YamlConfiguration config, String description) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            this.plugin.getLogger().severe("Failed to create directory for " + description + ".");
            return false;
        }
        File temporary = new File(parent, target.getName() + ".tmp-" + System.nanoTime());
        try {
            config.save(temporary);
            DurableFiles.replace(temporary.toPath(), target.toPath());
            return true;
        }
        catch (DurableFiles.ReplaceException exception) {
            if (exception.isTargetStateUncertain()) {
                this.failClosed(
                    "uncertain durable replacement for " + description,
                    exception
                );
            }
            this.plugin.getLogger().severe(
                "Failed to save " + description + ": "
                    + exception.getMessage()
            );
            return false;
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save " + description + ": " + e.getMessage());
            return false;
        }
        finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private void movePath(File source, File target) throws IOException {
        DurableFiles.moveReplacing(source.toPath(), target.toPath());
    }

    private void forceWarehouseDirectory(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException(
                "failed to list warehouse directory for durability check"
            );
        }
        for (File file : files) {
            if (!file.isFile()) {
                throw new IOException(
                    "unexpected nested path in warehouse directory: "
                        + file.getName()
                );
            }
            DurableFiles.forceFile(file.toPath());
        }
        DurableFiles.forceDirectoryIfSupported(directory.toPath());
    }

    private void recoverLegacyWarehouseDirectoryIfNeeded() {
        if (this.legacyWarehouseFolder.exists()) {
            return;
        }
        File[] backups = this.dataFolder.listFiles(
            (directory, name) -> name.startsWith("warehouse.bak-")
        );
        if (backups == null) {
            throw new IllegalStateException(
                "Failed to enumerate legacy warehouse backups."
            );
        }
        if (backups.length == 0) {
            return;
        }
        File newest = null;
        for (File backup : backups) {
            if (!backup.isDirectory()) {
                continue;
            }
            if (newest == null
                || backup.lastModified() > newest.lastModified()
                || (backup.lastModified() == newest.lastModified()
                    && backup.getName().compareTo(newest.getName()) > 0)) {
                newest = backup;
            }
        }
        if (newest == null) {
            return;
        }
        try {
            this.movePath(newest, this.legacyWarehouseFolder);
            this.forceWarehouseDirectory(this.legacyWarehouseFolder);
            DurableFiles.forceDirectoryIfSupported(this.dataFolder.toPath());
            this.plugin.getLogger().warning(
                "[AssetAudit] Recovered legacy warehouse directory from "
                    + newest.getName()
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to recover interrupted legacy warehouse directory replacement",
                exception
            );
        }
    }

    private void archiveLegacyWarehouseDirectory() {
        if (!this.legacyWarehouseFolder.exists()) {
            return;
        }
        if (!this.legacyWarehouseFolder.isDirectory()) {
            throw new IllegalStateException(
                "Legacy warehouse path is not a directory."
            );
        }
        File archive = new File(
            this.dataFolder,
            "warehouse.migrated-" + System.currentTimeMillis()
        );
        int suffix = 0;
        while (archive.exists()) {
            archive = new File(
                this.dataFolder,
                "warehouse.migrated-" + System.currentTimeMillis()
                    + "-" + ++suffix
            );
        }
        try {
            this.forceWarehouseDirectory(this.legacyWarehouseFolder);
            this.movePath(this.legacyWarehouseFolder, archive);
            DurableFiles.forceDirectoryIfSupported(this.dataFolder.toPath());
            this.plugin.getLogger().info(
                "Archived legacy warehouse directory as "
                    + archive.getName()
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to archive the migrated legacy warehouse directory.",
                exception
            );
        }
    }

    private boolean deleteFileDurably(File file, String description) {
        if (!file.exists()) {
            return true;
        }
        try {
            Files.delete(file.toPath());
            DurableFiles.forceDirectoryIfSupported(
                file.toPath().toAbsolutePath().getParent()
            );
            return true;
        } catch (IOException exception) {
            this.plugin.getLogger().severe(
                "Failed to delete " + description + ": "
                    + exception.getMessage()
            );
            return false;
        }
    }

    private void failClosed(String reason, Throwable cause) {
        this.plugin.markStorageUnavailable(reason, cause);
        throw new IllegalStateException(reason, cause);
    }

}
