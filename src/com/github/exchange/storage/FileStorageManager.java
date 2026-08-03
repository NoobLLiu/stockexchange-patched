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
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.InventoryDelivery;
import com.github.exchange.util.ItemSerializer;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.ConfigurationSection;
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
    private final File warehouseFolder;
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
        this.warehouseFolder = new File(this.dataFolder, "warehouse");
        this.dailyRegisterLimitFile = new File(this.dataFolder, "daily-register-limits.yml");
    }

    @Override
    public void init() {
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
        if (!this.itemsFolder.exists()) {
            this.itemsFolder.mkdirs();
        }
        if (!this.tradesFolder.exists()) {
            this.tradesFolder.mkdirs();
        }
        if (!this.ordersFolder.exists()) {
            this.ordersFolder.mkdirs();
        }
        if (!this.warehouseFolder.exists()) {
            this.warehouseFolder.mkdirs();
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
        this.saveIdCounters();
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
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save ID counters: " + e.getMessage());
        }
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
        this.saveExchangeItem(item);
        this.saveIdCounters();
        return id;
    }

    private void saveExchangeItem(ExchangeItem item) {
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
        config.set("last_empty_at", (Object)(item.getLastEmptyAt() != null ? item.getLastEmptyAt().getTime() : 0L));
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save item " + item.getId() + ": " + e.getMessage());
        }
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
    public void deleteExchangeItem(int itemId) {
        ExchangeItem removed = this.itemCache.remove(itemId);
        if (removed != null) {
            this.warehouseCache.remove(removed.getItemBase64());
            this.saveWarehouse();
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
        this.saveOrdersForItem(itemId);
        File file = new File(this.itemsFolder, itemId + ".yml");
        if (file.exists()) {
            file.delete();
        }
        File tradesFile = new File(this.tradesFolder, "item_" + itemId + ".yml");
        if (tradesFile.exists()) {
            tradesFile.delete();
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
                Order order = new Order(sec.getInt("id"), Order.OrderType.valueOf(sec.getString("order_type")), sec.getInt("item_id"), sec.getString("player_uuid"), new BigDecimal(sec.getString("price")), sec.getInt("quantity"), sec.getInt("filled_qty"), Order.OrderStatus.valueOf(sec.getString("status")), new Timestamp(sec.getLong("created_at")), new Timestamp(sec.getLong("updated_at")));
                order.setPlayerName(sec.getString("player_name", ""));
                this.orderCache.put(order.getId(), order);
                if (order.getId() <= maxId) continue;
                maxId = order.getId();
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
        this.saveOrdersForItem(order.getItemId());
        this.saveIdCounters();
        return id;
    }

    @Override
    public void updateOrder(Order order) {
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        this.orderCache.put(order.getId(), order);
        this.saveOrdersForItem(order.getItemId());
    }

    private void saveOrdersForItem(int itemId) {
        File file = new File(this.ordersFolder, "item_" + itemId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        int count = 0;
        for (Order order : this.orderCache.values()) {
            if (order.getItemId() != itemId) continue;
            String key = String.valueOf(order.getId());
            config.set(key + ".id", (Object)order.getId());
            config.set(key + ".order_type", (Object)order.getOrderType().name());
            config.set(key + ".item_id", (Object)order.getItemId());
            config.set(key + ".player_uuid", (Object)order.getPlayerUuid());
            config.set(key + ".player_name", (Object)(order.getPlayerName() != null ? order.getPlayerName() : ""));
            config.set(key + ".price", (Object)order.getPrice().toString());
            config.set(key + ".quantity", (Object)order.getQuantity());
            config.set(key + ".filled_qty", (Object)order.getFilledQty());
            config.set(key + ".status", (Object)order.getStatus().name());
            config.set(key + ".created_at", (Object)order.getCreatedAt().getTime());
            config.set(key + ".updated_at", (Object)order.getUpdatedAt().getTime());
            ++count;
        }
        try {
            if (count > 0) {
                config.save(file);
            } else if (file.exists()) {
                file.delete();
            }
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save orders for item " + itemId + ": " + e.getMessage());
        }
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
    public List<Order> getOrdersByPlayer(String playerUuid) {
        ArrayList<Order> result = new ArrayList<Order>();
        for (Order order : this.orderCache.values()) {
            if (!order.getPlayerUuid().equals(playerUuid)) continue;
            result.add(order);
        }
        result.sort((a, b) -> Long.compare(b.getCreatedAt().getTime(), a.getCreatedAt().getTime()));
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
                Trade trade = new Trade(sec.getInt("id"), sec.getInt("item_id"), sec.getString("buyer_uuid"), sec.getString("seller_uuid"), new BigDecimal(sec.getString("price")), sec.getInt("quantity"), new BigDecimal(sec.getString("total_amount")), new BigDecimal(sec.getString("buyer_fee")), new BigDecimal(sec.getString("seller_fee")), sec.getInt("buy_order_id"), sec.getInt("sell_order_id"), new Timestamp(sec.getLong("traded_at")));
                this.tradeCache.put(trade.getId(), trade);
                if (trade.getId() <= maxId) continue;
                maxId = trade.getId();
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
        this.saveTradesForItem(trade.getItemId());
        this.saveIdCounters();
        return id;
    }

    private void saveTradesForItem(int itemId) {
        File file = new File(this.tradesFolder, "item_" + itemId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        int count = 0;
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
            ++count;
        }
        try {
            if (count > 0) {
                config.save(file);
            } else if (file.exists()) {
                file.delete();
            }
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save trades for item " + itemId + ": " + e.getMessage());
        }
    }

    @Override
    public List<Trade> getTradesByPlayer(String playerUuid, int limit, int offset) {
        ArrayList<Trade> result = new ArrayList<Trade>();
        for (Trade trade : this.tradeCache.values()) {
            if (!trade.getBuyerUuid().equals(playerUuid) && !trade.getSellerUuid().equals(playerUuid)) continue;
            result.add(trade);
        }
        result.sort((a, b) -> Long.compare(b.getTradedAt().getTime(), a.getTradedAt().getTime()));
        int fromIndex = Math.min(offset, result.size());
        int toIndex = Math.min(offset + limit, result.size());
        if (fromIndex >= toIndex) {
            return new ArrayList<Trade>();
        }
        return result.subList(fromIndex, toIndex);
    }

    @Override
    public List<Trade> getTradesByItem(int itemId, int limit) {
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
    public List<Trade> getAllTrades(int limit) {
        ArrayList<Trade> result = new ArrayList<Trade>(this.tradeCache.values());
        result.sort((a, b) -> Long.compare(b.getTradedAt().getTime(), a.getTradedAt().getTime()));
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<Trade>(result.subList(0, limit));
        }
        return result;
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
            EscrowEntry entry = new EscrowEntry(sec.getInt("order_id"), sec.getString("player_uuid"), EscrowEntry.AssetType.valueOf(sec.getString("asset_type")), sec.contains("amount") ? new BigDecimal(sec.getString("amount")) : BigDecimal.ZERO, sec.getString("item_base64"), sec.getInt("quantity"));
            this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
        }
    }

    private String escrowKey(int orderId, EscrowEntry.AssetType type) {
        return orderId + "_" + type.name();
    }

    @Override
    public void insertEscrow(EscrowEntry entry) {
        this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
        this.saveAllEscrow();
    }

    @Override
    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return this.escrowCache.get(this.escrowKey(orderId, assetType));
    }

    @Override
    public void deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        this.escrowCache.remove(this.escrowKey(orderId, assetType));
        this.saveAllEscrow();
    }

    private void saveAllEscrow() {
        File file = new File(this.dataFolder, "escrow.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (EscrowEntry entry : this.escrowCache.values()) {
            String key = this.escrowKey(entry.getOrderId(), entry.getAssetType());
            config.set(key + ".order_id", (Object)entry.getOrderId());
            config.set(key + ".player_uuid", (Object)entry.getPlayerUuid());
            config.set(key + ".asset_type", (Object)entry.getAssetType().name());
            config.set(key + ".amount", (Object)(entry.getAmount() != null ? entry.getAmount().toString() : "0"));
            config.set(key + ".item_base64", (Object)entry.getItemBase64());
            config.set(key + ".quantity", (Object)entry.getQuantity());
        }
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save escrow: " + e.getMessage());
        }
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
            ItemStatus status = new ItemStatus(sec.getInt("item_id"), sec.getBoolean("is_suspended"), new BigDecimal(sec.getString("last_close")), new BigDecimal(sec.getString("last_open")), new BigDecimal(sec.getString("high_today")), new BigDecimal(sec.getString("low_today")), sec.getInt("volume_today"));
            status.setLowestSellCurrent(new BigDecimal(sec.getString("lowest_sell_current", "0")));
            status.setLowestSellReference(new BigDecimal(sec.getString("lowest_sell_reference", "0")));
            status.setLowestSellReferenceAt(sec.getLong("lowest_sell_reference_at", 0L));
            status.setLowestSellReference7d(new BigDecimal(sec.getString("lowest_sell_reference_7d", sec.getString("lowest_sell_reference", "0"))));
            status.setLowestSellReferenceAt7d(sec.getLong("lowest_sell_reference_at_7d", sec.getLong("lowest_sell_reference_at", 0L)));
            status.setLowestSellReference30d(new BigDecimal(sec.getString("lowest_sell_reference_30d", sec.getString("lowest_sell_reference", "0"))));
            status.setLowestSellReferenceAt30d(sec.getLong("lowest_sell_reference_at_30d", sec.getLong("lowest_sell_reference_at", 0L)));
            this.statusCache.put(status.getItemId(), status);
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
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save statuses: " + e.getMessage());
        }
    }

    private void loadAllWarehouse() {
        File[] files = this.warehouseFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration((File)file);
            String itemBase64 = config.getString("item_base64");
            int quantity = config.getInt("quantity", 0);
            if (itemBase64 == null || quantity <= 0) continue;
            this.warehouseCache.put(itemBase64, this.warehouseCache.getOrDefault(itemBase64, 0) + quantity);
        }
    }

    @Override
    public void addToWarehouse(String itemBase64, int quantity) {
        if (itemBase64 == null || quantity <= 0) {
            return;
        }
        this.warehouseCache.put(itemBase64, this.warehouseCache.getOrDefault(itemBase64, 0) + quantity);
        this.markItemStocked(itemBase64);
        this.saveWarehouse();
    }

    @Override
    public int getWarehouseQuantity(String itemBase64) {
        return this.warehouseCache.getOrDefault(itemBase64, 0);
    }

    @Override
    public boolean takeFromWarehouse(String itemBase64, int quantity) {
        if (itemBase64 == null || quantity <= 0) {
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
        this.markItemEmptyState(itemBase64);
        this.saveWarehouse();
        return true;
    }

    @Override
    public Map<String, Integer> getWarehouseSnapshot() {
        return new HashMap<String, Integer>(this.warehouseCache);
    }

    private void saveWarehouse() {
        File[] oldFiles = this.warehouseFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (oldFiles != null) {
            for (File f : oldFiles) {
                f.delete();
            }
        }
        int index = 0;
        for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
            if (entry.getValue() <= 0) continue;
            File file = new File(this.warehouseFolder, index + ".yml");
            YamlConfiguration config = new YamlConfiguration();
            config.set("item_base64", (Object)entry.getKey());
            config.set("quantity", (Object)entry.getValue());
            try {
                config.save(file);
            }
            catch (IOException e) {
                this.plugin.getLogger().severe("Failed to save warehouse entry: " + e.getMessage());
            }
            ++index;
        }
    }

    private void markItemStocked(String itemBase64) {
        ExchangeItem item = this.findItemByBase64(itemBase64);
        if (item == null) {
            return;
        }
        item.setLastStockedAt(new Timestamp(System.currentTimeMillis()));
        item.setLastEmptyAt(null);
        this.saveExchangeItem(item);
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

    private void saveDailyRegisterLimits() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Integer>> playerEntry : this.dailyRegisterLimitCache.entrySet()) {
            for (Map.Entry<String, Integer> dayEntry : playerEntry.getValue().entrySet()) {
                config.set(playerEntry.getKey() + "." + dayEntry.getKey(), dayEntry.getValue());
            }
        }
        try {
            config.save(this.dailyRegisterLimitFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save daily register limits: " + e.getMessage());
        }
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
    public void setDailyRegisterCount(String playerUuid, LocalDate date, int count) {
        if (playerUuid == null || date == null) {
            return;
        }
        Map<String, Integer> counts = this.dailyRegisterLimitCache.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<String, Integer>());
        String dateKey = date.toString();
        if (count <= 0) {
            counts.remove(dateKey);
            if (counts.isEmpty()) {
                this.dailyRegisterLimitCache.remove(playerUuid);
            }
        } else {
            counts.put(dateKey, count);
        }
        this.saveDailyRegisterLimits();
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
            String playerUuid = sec.getString("player_uuid");
            BigDecimal amount = new BigDecimal(sec.getString("amount", "0"));
            if (playerUuid == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
            this.moneyWarehouseCache.put(playerUuid, this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO).add(amount));
        }
    }

    @Override
    public void addToMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        this.moneyWarehouseCache.put(playerUuid, this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO).add(amount));
        this.saveMoneyWarehouse();
    }

    @Override
    public BigDecimal getMoneyWarehouseBalance(String playerUuid) {
        return this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
    }

    @Override
    public boolean takeFromMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
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
        this.saveMoneyWarehouse();
        return true;
    }

    private String getPlayerWarehouseKey(String playerUuid, String itemBase64) {
        return PLAYER_WAREHOUSE_PREFIX + playerUuid + "|" + itemBase64;
    }

    @Override
    public void addToPlayerItemWarehouse(String playerUuid, String itemBase64, int quantity) {
        if (playerUuid == null || itemBase64 == null || quantity <= 0) {
            return;
        }
        String key = this.getPlayerWarehouseKey(playerUuid, itemBase64);
        this.warehouseCache.put(key, this.warehouseCache.getOrDefault(key, 0) + quantity);
        this.saveWarehouse();
    }

    @Override
    public Map<String, Integer> getPlayerItemWarehouse(String playerUuid) {
        HashMap<String, Integer> snapshot = new HashMap<String, Integer>();
        if (playerUuid == null || playerUuid.isEmpty()) {
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
    public boolean takeFromPlayerItemWarehouse(String playerUuid, String itemBase64, int quantity) {
        if (playerUuid == null || itemBase64 == null || quantity <= 0) {
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
        this.saveWarehouse();
        return true;
    }

    private void saveMoneyWarehouse() {
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
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Failed to save money warehouse: " + e.getMessage());
        }
    }

    @Override
    public void withdrawWarehouse(Player player) {
        String playerUuid = player.getUniqueId().toString();
        BigDecimal moneyBalance = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        if (moneyBalance.compareTo(BigDecimal.ZERO) > 0) {
            EconomyUtil.deposit(player.getUniqueId(), moneyBalance);
            this.moneyWarehouseCache.remove(playerUuid);
            this.saveMoneyWarehouse();
            player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + moneyBalance + " \u00a7a\u91d1\u5e01\u3002");
        }
        int totalItems = 0;
        HashMap<String, Integer> toRemove = new HashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
            ItemStack itemStack;
            if (entry.getValue() <= 0 || (itemStack = ItemSerializer.itemFromBase64(entry.getKey())) == null) continue;
            int quantity = entry.getValue();
            int added = InventoryDelivery.addUpTo(player, itemStack, quantity);
            if (added > 0) {
                toRemove.put(entry.getKey(), added);
                totalItems += added;
            }
            if (added < quantity) {
                player.sendMessage("\u00a7c\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u90e8\u5206\u7269\u54c1\u65e0\u6cd5\u63d0\u53d6\u3002");
                break;
            }
        }
        for (Map.Entry<String, Integer> entry : toRemove.entrySet()) {
            int current = this.warehouseCache.getOrDefault(entry.getKey(), 0);
            int remaining = current - entry.getValue();
            if (remaining <= 0) {
                this.warehouseCache.remove(entry.getKey());
                continue;
            }
            this.warehouseCache.put(entry.getKey(), remaining);
        }
        this.saveWarehouse();
        if (totalItems > 0) {
            player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + totalItems + " \u00a7a\u4e2a\u7269\u54c1\u3002");
        }
        if (moneyBalance.compareTo(BigDecimal.ZERO) <= 0 && totalItems <= 0) {
            player.sendMessage("\u00a77\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u63d0\u53d6\u7684\u7269\u54c1\u6216\u91d1\u5e01\u3002");
        }
    }

    @Override
    public void withdrawWarehouseMoney(Player player) {
        String playerUuid = player.getUniqueId().toString();
        BigDecimal moneyBalance = this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
        if (moneyBalance.compareTo(BigDecimal.ZERO) <= 0) {
            player.sendMessage("\u00a77\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u63d0\u53d6\u7684\u91d1\u5e01\u3002");
            return;
        }
        EconomyUtil.deposit(player.getUniqueId(), moneyBalance);
        this.moneyWarehouseCache.remove(playerUuid);
        this.saveMoneyWarehouse();
        player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + moneyBalance + " \u00a7a\u91d1\u5e01\u3002");
    }

    @Override
    public void withdrawWarehouseItem(Player player, String itemBase64) {
        if (itemBase64 == null || itemBase64.isEmpty()) {
            player.sendMessage("\u00a7c\u65e0\u6548\u7684\u4ed3\u5e93\u7269\u54c1\u3002");
            return;
        }
        int quantity = this.warehouseCache.getOrDefault(itemBase64, 0);
        if (quantity <= 0) {
            player.sendMessage("\u00a77\u8be5\u7269\u54c1\u5df2\u88ab\u63d0\u53d6\u6216\u4e0d\u5b58\u5728\u3002");
            return;
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(itemBase64);
        if (itemStack == null) {
            player.sendMessage("\u00a7c\u7269\u54c1\u6570\u636e\u635f\u574f\uff0c\u65e0\u6cd5\u63d0\u53d6\u3002");
            return;
        }
        int added = InventoryDelivery.addUpTo(player, itemStack, quantity);
        if (added <= 0) {
            player.sendMessage("\u00a7c\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u65e0\u6cd5\u63d0\u53d6\u8be5\u7269\u54c1\u3002");
            return;
        }
        int remaining = quantity - added;
        if (remaining <= 0) {
            this.warehouseCache.remove(itemBase64);
        } else {
            this.warehouseCache.put(itemBase64, remaining);
            player.sendMessage("\u00a7c\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u90e8\u5206\u7269\u54c1\u672a\u63d0\u53d6\u3002");
        }
        this.saveWarehouse();
        player.sendMessage("\u00a7a\u5df2\u63d0\u53d6 \u00a7f" + added + " \u00a7a\u4e2a\u7269\u54c1\u3002");
    }
}
