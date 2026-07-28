/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class MySQLStorageManager
implements StorageManager {
    private static final String PLAYER_WAREHOUSE_PREFIX = "player:";
    private final StockExchangePlugin plugin;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private Connection connection;
    private String activeDriverClass = "unknown";
    private final Object dbLock = new Object();
    private final Map<Integer, ExchangeItem> itemCache = new ConcurrentHashMap<Integer, ExchangeItem>();
    private final Map<Integer, Order> orderCache = new ConcurrentHashMap<Integer, Order>();
    private final Map<Integer, Trade> tradeCache = new ConcurrentHashMap<Integer, Trade>();
    private final Map<String, EscrowEntry> escrowCache = new ConcurrentHashMap<String, EscrowEntry>();
    private final Map<Integer, ItemStatus> statusCache = new ConcurrentHashMap<Integer, ItemStatus>();
    private final Map<String, Integer> warehouseCache = new ConcurrentHashMap<String, Integer>();
    private final Map<String, BigDecimal> moneyWarehouseCache = new ConcurrentHashMap<String, BigDecimal>();

    public MySQLStorageManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("database.mysql.host", "localhost");
        this.port = plugin.getConfig().getInt("database.mysql.port", 3306);
        this.database = plugin.getConfig().getString("database.mysql.database", "exchange");
        this.username = plugin.getConfig().getString("database.mysql.username", "root");
        this.password = plugin.getConfig().getString("database.mysql.password", "");
    }

    @Override
    public void init() {
        if (!this.loadCompatibleDriver()) {
            this.plugin.getLogger().severe("MySQL JDBC driver not found! Please check plugin jar dependency packaging.");
            return;
        }
        this.connect();
        if (this.connection == null) {
            throw new IllegalStateException("MySQL connection is null after connect(). Check previous MySQL error logs for SQLState/vendorCode and URL settings.");
        }
        this.createTables();
        this.loadAllData();
        this.plugin.getLogger().info("MySQL storage initialized. Items: " + this.itemCache.size() + ", Orders: " + this.orderCache.size() + ", Trades: " + this.tradeCache.size());
    }

    private boolean loadCompatibleDriver() {
        String[] candidates;
        for (String driverClass : candidates = new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"}) {
            try {
                Class.forName(driverClass);
                this.activeDriverClass = driverClass;
                this.plugin.getLogger().info("MySQL driver loaded: " + driverClass);
                return true;
            }
            catch (ClassNotFoundException classNotFoundException) {
            }
        }
        return false;
    }

    private void connect() {
        String baseHostUrl = "jdbc:mysql://" + this.host + ":" + this.port + "/";
        String dbHostUrl = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database;
        List<Properties> propertyProfiles = this.buildConnectionPropertyProfiles();
        try {
            SQLException bootstrapException = null;
            for (Properties profile : propertyProfiles) {
                try (Connection bootstrapConnection = DriverManager.getConnection(baseHostUrl, this.mergeCredentials(profile));){
                    Statement bootstrapStatement = bootstrapConnection.createStatement();
                    try {
                        bootstrapStatement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + this.database + "` DEFAULT CHARACTER SET utf8mb4");
                        this.plugin.getLogger().info("Database ensured (exists or created): " + this.database);
                        bootstrapException = null;
                        if (bootstrapStatement == null) break;
                    }
                    catch (Throwable throwable) {
                        if (bootstrapStatement != null) {
                            try {
                                bootstrapStatement.close();
                            }
                            catch (Throwable throwable2) {
                                throwable.addSuppressed(throwable2);
                            }
                        }
                        throw throwable;
                    }
                    bootstrapStatement.close();
                    break;
                }
                catch (SQLException ex) {
                    bootstrapException = ex;
                }
            }
            if (bootstrapException != null) {
                throw bootstrapException;
            }
            this.connection = null;
            SQLException lastConnectionException = null;
            for (Properties profile : propertyProfiles) {
                try {
                    this.connection = DriverManager.getConnection(dbHostUrl, this.mergeCredentials(profile));
                    lastConnectionException = null;
                    break;
                }
                catch (SQLException ex) {
                    lastConnectionException = ex;
                }
            }
            if (this.connection == null && lastConnectionException != null) {
                throw lastConnectionException;
            }
            this.plugin.getLogger().info("Connected to MySQL database: " + this.database);
            this.plugin.getLogger().info("MySQL endpoint: " + this.host + ":" + this.port + ", user=" + this.username + ", db=" + this.database);
            this.plugin.getLogger().info("MySQL compatibility mode: driver=" + this.activeDriverClass + " (supports MySQL 5.x/8.x/9.x)");
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to connect to MySQL: " + e.getMessage());
            this.plugin.getLogger().severe("MySQL details => host=" + this.host + ", port=" + this.port + ", db=" + this.database + ", user=" + this.username);
            this.plugin.getLogger().severe("SQLState=" + e.getSQLState() + ", vendorCode=" + e.getErrorCode());
            this.plugin.getLogger().severe("Likely causes: MySQL service not running, wrong host/port, wrong user/password, user has no privileges, or server blocks this client.");
            e.printStackTrace();
        }
    }

    private List<Properties> buildConnectionPropertyProfiles() {
        ArrayList<Properties> profiles = new ArrayList<Properties>();
        Properties modern = new Properties();
        modern.setProperty("useUnicode", "true");
        modern.setProperty("characterEncoding", "utf8");
        modern.setProperty("useSSL", "false");
        modern.setProperty("allowPublicKeyRetrieval", "true");
        modern.setProperty("serverTimezone", "Asia/Shanghai");
        modern.setProperty("connectionTimeZone", "LOCAL");
        profiles.add(modern);
        Properties legacy = new Properties();
        legacy.setProperty("useUnicode", "true");
        legacy.setProperty("characterEncoding", "utf8");
        legacy.setProperty("useSSL", "false");
        legacy.setProperty("serverTimezone", "Asia/Shanghai");
        profiles.add(legacy);
        return profiles;
    }

    private Properties mergeCredentials(Properties baseProperties) {
        Properties merged = new Properties();
        merged.putAll((Map<?, ?>)baseProperties);
        merged.setProperty("user", this.username);
        merged.setProperty("password", this.password);
        return merged;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private boolean ensureConnectionAvailable() {
        Object object = this.dbLock;
        synchronized (object) {
            try {
                if (this.connection != null && !this.connection.isClosed() && this.connection.isValid(2)) {
                    return true;
                }
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            this.connect();
            if (this.connection == null) {
                this.plugin.getLogger().severe("MySQL connection unavailable after reconnect attempt.");
                return false;
            }
            this.createTables();
            return true;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void refreshAllCachesFromDatabase() {
        Object object = this.dbLock;
        synchronized (object) {
            this.itemCache.clear();
            this.orderCache.clear();
            this.tradeCache.clear();
            this.escrowCache.clear();
            this.statusCache.clear();
            this.warehouseCache.clear();
            this.moneyWarehouseCache.clear();
            this.loadAllData();
        }
    }

    private boolean prepareForOperation(boolean refreshBeforeRead) {
        if (!this.ensureConnectionAvailable()) {
            return false;
        }
        if (refreshBeforeRead) {
            this.refreshAllCachesFromDatabase();
        }
        return true;
    }

    private void createTables() {
        try (Statement stmt = this.connection.createStatement();){
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS exchange_items (  id INT PRIMARY KEY AUTO_INCREMENT,  material VARCHAR(64) NOT NULL,  nbt_hash VARCHAR(64) NOT NULL,  item_base64 TEXT NOT NULL,  display_name VARCHAR(256) NOT NULL,  item_name VARCHAR(256) DEFAULT '',  item_lore TEXT,  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,  UNIQUE KEY uk_material_nbt (material, nbt_hash)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try {
                stmt.executeUpdate("ALTER TABLE exchange_items ADD COLUMN item_name VARCHAR(256) DEFAULT ''");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE exchange_items ADD COLUMN item_lore TEXT");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS orders (  id INT PRIMARY KEY AUTO_INCREMENT,  order_type VARCHAR(10) NOT NULL,  item_id INT NOT NULL,  player_uuid VARCHAR(36) NOT NULL,  player_name VARCHAR(32) DEFAULT '',  price DECIMAL(16,2) NOT NULL,  quantity INT NOT NULL,  filled_qty INT DEFAULT 0,  status VARCHAR(20) NOT NULL,  created_at BIGINT NOT NULL,  updated_at BIGINT NOT NULL,  KEY idx_item_status (item_id, status),  KEY idx_player (player_uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS trades (  id INT PRIMARY KEY AUTO_INCREMENT,  item_id INT NOT NULL,  buyer_uuid VARCHAR(36) NOT NULL,  seller_uuid VARCHAR(36) NOT NULL,  price DECIMAL(16,2) NOT NULL,  quantity INT NOT NULL,  total_amount DECIMAL(16,2) NOT NULL,  buyer_fee DECIMAL(16,2) DEFAULT 0,  seller_fee DECIMAL(16,2) DEFAULT 0,  buy_order_id INT DEFAULT 0,  sell_order_id INT DEFAULT 0,  traded_at BIGINT NOT NULL,  KEY idx_item (item_id),  KEY idx_buyer (buyer_uuid),  KEY idx_seller (seller_uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS escrow (  order_id INT NOT NULL,  player_uuid VARCHAR(36) NOT NULL,  asset_type VARCHAR(10) NOT NULL,  amount DECIMAL(16,2) DEFAULT 0,  item_base64 TEXT,  quantity INT DEFAULT 0,  PRIMARY KEY (order_id, asset_type)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS item_status (  item_id INT PRIMARY KEY,  is_suspended BOOLEAN DEFAULT FALSE,  last_close DECIMAL(16,2) DEFAULT 0,  last_open DECIMAL(16,2) DEFAULT 0,  high_today DECIMAL(16,2) DEFAULT 0,  low_today DECIMAL(16,2) DEFAULT 0,  volume_today INT DEFAULT 0,  lowest_sell_current DECIMAL(16,2) DEFAULT 0,  lowest_sell_reference DECIMAL(16,2) DEFAULT 0,  lowest_sell_reference_at BIGINT DEFAULT 0,  lowest_sell_reference_7d DECIMAL(16,2) DEFAULT 0,  lowest_sell_reference_at_7d BIGINT DEFAULT 0,  lowest_sell_reference_30d DECIMAL(16,2) DEFAULT 0,  lowest_sell_reference_at_30d BIGINT DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_current DECIMAL(16,2) DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference DECIMAL(16,2) DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference_at BIGINT DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference_7d DECIMAL(16,2) DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference_at_7d BIGINT DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference_30d DECIMAL(16,2) DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            try {
                stmt.executeUpdate("ALTER TABLE item_status ADD COLUMN lowest_sell_reference_at_30d BIGINT DEFAULT 0");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS warehouse (  id INT PRIMARY KEY AUTO_INCREMENT,  item_base64 TEXT NOT NULL,  quantity INT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS money_warehouse (  player_uuid VARCHAR(36) PRIMARY KEY,  amount DECIMAL(16,2) DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            this.plugin.getLogger().info("Database tables created/verified.");
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
        }
    }

    private void loadAllData() {
        this.loadAllItems();
        this.loadAllOrders();
        this.loadAllTrades();
        this.loadAllEscrow();
        this.loadAllStatuses();
        this.loadAllWarehouse();
        this.loadAllMoneyWarehouse();
    }

    private void loadAllItems() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM exchange_items");){
            while (rs.next()) {
                ExchangeItem item = new ExchangeItem(rs.getInt("id"), rs.getString("material"), rs.getString("nbt_hash"), rs.getString("item_base64"), rs.getString("display_name"), rs.getTimestamp("created_at"));
                item.setItemName(rs.getString("item_name"));
                item.setItemLore(rs.getString("item_lore"));
                this.itemCache.put(item.getId(), item);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load items: " + e.getMessage());
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Override
    public int insertExchangeItem(ExchangeItem item) {
        if (!this.prepareForOperation(false)) {
            return -1;
        }
        String sql = "INSERT INTO exchange_items (material, nbt_hash, item_base64, display_name, item_name, item_lore) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql, 1);){
            ps.setString(1, item.getMaterial());
            ps.setString(2, item.getNbtHash());
            ps.setString(3, item.getItemBase64());
            ps.setString(4, item.getDisplayName());
            ps.setString(5, item.getItemName() != null ? item.getItemName() : item.getDisplayName());
            ps.setString(6, item.getItemLore() != null ? item.getItemLore() : "");
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (!rs.next()) return -1;
            int id = rs.getInt(1);
            item.setId(id);
            this.itemCache.put(id, item);
            int n = id;
            return n;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to insert item: " + e.getMessage());
        }
        return -1;
    }

    @Override
    public void updateExchangeItem(ExchangeItem item) {
        if (!this.prepareForOperation(false) || item == null || item.getId() <= 0) {
            return;
        }
        String sql = "UPDATE exchange_items SET material=?, nbt_hash=?, item_base64=?, display_name=?, item_name=?, item_lore=? WHERE id=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setString(1, item.getMaterial());
            ps.setString(2, item.getNbtHash());
            ps.setString(3, item.getItemBase64());
            ps.setString(4, item.getDisplayName());
            ps.setString(5, item.getItemName() != null ? item.getItemName() : item.getDisplayName());
            ps.setString(6, item.getItemLore() != null ? item.getItemLore() : "");
            ps.setInt(7, item.getId());
            ps.executeUpdate();
            this.itemCache.put(item.getId(), item);
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to update item: " + e.getMessage());
        }
    }

    @Override
    public void deleteExchangeItem(int itemId) {
        if (!this.prepareForOperation(false)) {
            return;
        }
        try (PreparedStatement ps = this.connection.prepareStatement("DELETE FROM exchange_items WHERE id=?");){
            ps.setInt(1, itemId);
            ps.executeUpdate();
            this.itemCache.remove(itemId);
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to delete item: " + e.getMessage());
        }
    }

    @Override
    public ExchangeItem getExchangeItem(int id) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        return this.itemCache.get(id);
    }

    @Override
    public ExchangeItem getExchangeItemByHash(String material, String nbtHash) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        for (ExchangeItem item : this.itemCache.values()) {
            if (!item.getMaterial().equals(material) || !item.getNbtHash().equals(nbtHash)) continue;
            return item;
        }
        return null;
    }

    @Override
    public List<ExchangeItem> getAllExchangeItems() {
        if (!this.prepareForOperation(true)) {
            return new ArrayList<ExchangeItem>();
        }
        ArrayList<ExchangeItem> items = new ArrayList<ExchangeItem>(this.itemCache.values());
        items.sort(Comparator.comparingInt(ExchangeItem::getId));
        return items;
    }

    private void loadAllOrders() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM orders");){
            while (rs.next()) {
                Order order = new Order(rs.getInt("id"), Order.OrderType.valueOf(rs.getString("order_type")), rs.getInt("item_id"), rs.getString("player_uuid"), rs.getBigDecimal("price"), rs.getInt("quantity"), rs.getInt("filled_qty"), Order.OrderStatus.valueOf(rs.getString("status")), new Timestamp(rs.getLong("created_at")), new Timestamp(rs.getLong("updated_at")));
                order.setPlayerName(rs.getString("player_name"));
                this.orderCache.put(order.getId(), order);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load orders: " + e.getMessage());
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Override
    public int insertOrder(Order order) {
        if (!this.prepareForOperation(false)) {
            return -1;
        }
        String sql = "INSERT INTO orders (order_type, item_id, player_uuid, player_name, price, quantity, filled_qty, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql, 1);){
            ps.setString(1, order.getOrderType().name());
            ps.setInt(2, order.getItemId());
            ps.setString(3, order.getPlayerUuid());
            ps.setString(4, order.getPlayerName() != null ? order.getPlayerName() : "");
            ps.setBigDecimal(5, order.getPrice());
            ps.setInt(6, order.getQuantity());
            ps.setInt(7, order.getFilledQty());
            ps.setString(8, order.getStatus().name());
            long now = System.currentTimeMillis();
            ps.setLong(9, order.getCreatedAt() != null ? order.getCreatedAt().getTime() : now);
            ps.setLong(10, now);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (!rs.next()) return -1;
            int id = rs.getInt(1);
            order.setId(id);
            if (order.getCreatedAt() == null) {
                order.setCreatedAt(new Timestamp(now));
            }
            order.setUpdatedAt(new Timestamp(now));
            this.orderCache.put(id, order);
            int n = id;
            return n;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to insert order: " + e.getMessage());
        }
        return -1;
    }

    @Override
    public void updateOrder(Order order) {
        if (!this.prepareForOperation(false)) {
            return;
        }
        String sql = "UPDATE orders SET filled_qty=?, status=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, order.getFilledQty());
            ps.setString(2, order.getStatus().name());
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, order.getId());
            ps.executeUpdate();
            order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            this.orderCache.put(order.getId(), order);
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to update order " + order.getId() + ": " + e.getMessage());
        }
    }

    @Override
    public Order getOrder(int id) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        return this.orderCache.get(id);
    }

    @Override
    public List<Order> getActiveOrdersByItem(int itemId, Order.OrderType orderType) {
        if (!this.prepareForOperation(true)) {
            return new ArrayList<Order>();
        }
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
        if (!this.prepareForOperation(true)) {
            return new ArrayList<Order>();
        }
        ArrayList<Order> result = new ArrayList<Order>();
        for (Order order : this.orderCache.values()) {
            if (!order.getPlayerUuid().equals(playerUuid)) continue;
            result.add(order);
        }
        result.sort((a, b) -> Long.compare(b.getCreatedAt().getTime(), a.getCreatedAt().getTime()));
        return result;
    }

    private void loadAllTrades() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM trades");){
            while (rs.next()) {
                Trade trade = new Trade(rs.getInt("id"), rs.getInt("item_id"), rs.getString("buyer_uuid"), rs.getString("seller_uuid"), rs.getBigDecimal("price"), rs.getInt("quantity"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("buyer_fee"), rs.getBigDecimal("seller_fee"), rs.getInt("buy_order_id"), rs.getInt("sell_order_id"), new Timestamp(rs.getLong("traded_at")));
                this.tradeCache.put(trade.getId(), trade);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load trades: " + e.getMessage());
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Override
    public int insertTrade(Trade trade) {
        if (!this.prepareForOperation(false)) {
            return -1;
        }
        String sql = "INSERT INTO trades (item_id, buyer_uuid, seller_uuid, price, quantity, total_amount, buyer_fee, seller_fee, buy_order_id, sell_order_id, traded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql, 1);){
            ps.setInt(1, trade.getItemId());
            ps.setString(2, trade.getBuyerUuid());
            ps.setString(3, trade.getSellerUuid());
            ps.setBigDecimal(4, trade.getPrice());
            ps.setInt(5, trade.getQuantity());
            ps.setBigDecimal(6, trade.getTotalAmount());
            ps.setBigDecimal(7, trade.getBuyerFee());
            ps.setBigDecimal(8, trade.getSellerFee());
            ps.setInt(9, trade.getBuyOrderId());
            ps.setInt(10, trade.getSellOrderId());
            long now = System.currentTimeMillis();
            ps.setLong(11, trade.getTradedAt() != null ? trade.getTradedAt().getTime() : now);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (!rs.next()) return -1;
            int id = rs.getInt(1);
            trade.setId(id);
            if (trade.getTradedAt() == null) {
                trade.setTradedAt(new Timestamp(now));
            }
            this.tradeCache.put(id, trade);
            int n = id;
            return n;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to insert trade: " + e.getMessage());
        }
        return -1;
    }

    @Override
    public List<Trade> getTradesByPlayer(String playerUuid, int limit, int offset) {
        if (!this.prepareForOperation(true)) {
            return new ArrayList<Trade>();
        }
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
        if (!this.prepareForOperation(true)) {
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
    public Trade getLastTrade(int itemId) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        Trade last = null;
        for (Trade trade : this.tradeCache.values()) {
            if (trade.getItemId() != itemId || last != null && !trade.getTradedAt().after(last.getTradedAt())) continue;
            last = trade;
        }
        return last;
    }

    @Override
    public Trade getFirstTradeOfDate(int itemId, LocalDate date) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
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
        if (!this.prepareForOperation(true)) {
            return null;
        }
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
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM escrow");){
            while (rs.next()) {
                EscrowEntry entry = new EscrowEntry(rs.getInt("order_id"), rs.getString("player_uuid"), EscrowEntry.AssetType.valueOf(rs.getString("asset_type")), rs.getBigDecimal("amount"), rs.getString("item_base64"), rs.getInt("quantity"));
                this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load escrow: " + e.getMessage());
        }
    }

    private String escrowKey(int orderId, EscrowEntry.AssetType type) {
        return orderId + "_" + type.name();
    }

    @Override
    public void insertEscrow(EscrowEntry entry) {
        if (!this.prepareForOperation(false)) {
            return;
        }
        String sql = "REPLACE INTO escrow (order_id, player_uuid, asset_type, amount, item_base64, quantity) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, entry.getOrderId());
            ps.setString(2, entry.getPlayerUuid());
            ps.setString(3, entry.getAssetType().name());
            ps.setBigDecimal(4, entry.getAmount() != null ? entry.getAmount() : BigDecimal.ZERO);
            ps.setString(5, entry.getItemBase64() != null ? entry.getItemBase64() : "");
            ps.setInt(6, entry.getQuantity());
            ps.executeUpdate();
            this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to insert escrow: " + e.getMessage());
        }
    }

    @Override
    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        return this.escrowCache.get(this.escrowKey(orderId, assetType));
    }

    @Override
    public void deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        if (!this.prepareForOperation(false)) {
            return;
        }
        String sql = "DELETE FROM escrow WHERE order_id=? AND asset_type=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, orderId);
            ps.setString(2, assetType.name());
            ps.executeUpdate();
            this.escrowCache.remove(this.escrowKey(orderId, assetType));
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to delete escrow: " + e.getMessage());
        }
    }

    private void loadAllStatuses() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM item_status");){
            while (rs.next()) {
                ItemStatus status = new ItemStatus(rs.getInt("item_id"), rs.getBoolean("is_suspended"), rs.getBigDecimal("last_close"), rs.getBigDecimal("last_open"), rs.getBigDecimal("high_today"), rs.getBigDecimal("low_today"), rs.getInt("volume_today"));
                status.setLowestSellCurrent(rs.getBigDecimal("lowest_sell_current"));
                status.setLowestSellReference(rs.getBigDecimal("lowest_sell_reference"));
                status.setLowestSellReferenceAt(rs.getLong("lowest_sell_reference_at"));
                status.setLowestSellReference7d(rs.getBigDecimal("lowest_sell_reference_7d"));
                status.setLowestSellReferenceAt7d(rs.getLong("lowest_sell_reference_at_7d"));
                status.setLowestSellReference30d(rs.getBigDecimal("lowest_sell_reference_30d"));
                status.setLowestSellReferenceAt30d(rs.getLong("lowest_sell_reference_at_30d"));
                this.statusCache.put(status.getItemId(), status);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load statuses: " + e.getMessage());
        }
    }

    @Override
    public void upsertItemStatus(ItemStatus status) {
        if (!this.prepareForOperation(false)) {
            return;
        }
        String sql = "REPLACE INTO item_status (item_id, is_suspended, last_close, last_open, high_today, low_today, volume_today, lowest_sell_current, lowest_sell_reference, lowest_sell_reference_at, lowest_sell_reference_7d, lowest_sell_reference_at_7d, lowest_sell_reference_30d, lowest_sell_reference_at_30d) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, status.getItemId());
            ps.setBoolean(2, status.isSuspended());
            ps.setBigDecimal(3, status.getLastClose() != null ? status.getLastClose() : BigDecimal.ZERO);
            ps.setBigDecimal(4, status.getLastOpen() != null ? status.getLastOpen() : BigDecimal.ZERO);
            ps.setBigDecimal(5, status.getHighToday() != null ? status.getHighToday() : BigDecimal.ZERO);
            ps.setBigDecimal(6, status.getLowToday() != null ? status.getLowToday() : BigDecimal.ZERO);
            ps.setInt(7, status.getVolumeToday());
            ps.setBigDecimal(8, status.getLowestSellCurrent() != null ? status.getLowestSellCurrent() : BigDecimal.ZERO);
            ps.setBigDecimal(9, status.getLowestSellReference() != null ? status.getLowestSellReference() : BigDecimal.ZERO);
            ps.setLong(10, status.getLowestSellReferenceAt());
            ps.setBigDecimal(11, status.getLowestSellReference7d() != null ? status.getLowestSellReference7d() : BigDecimal.ZERO);
            ps.setLong(12, status.getLowestSellReferenceAt7d());
            ps.setBigDecimal(13, status.getLowestSellReference30d() != null ? status.getLowestSellReference30d() : BigDecimal.ZERO);
            ps.setLong(14, status.getLowestSellReferenceAt30d());
            ps.executeUpdate();
            this.statusCache.put(status.getItemId(), status);
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to upsert status: " + e.getMessage());
        }
    }

    @Override
    public ItemStatus getItemStatus(int itemId) {
        if (!this.prepareForOperation(true)) {
            return null;
        }
        return this.statusCache.get(itemId);
    }

    private void loadAllWarehouse() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM warehouse");){
            while (rs.next()) {
                String itemBase64 = rs.getString("item_base64");
                int quantity = rs.getInt("quantity");
                if (itemBase64 == null || quantity <= 0) continue;
                this.warehouseCache.put(itemBase64, this.warehouseCache.getOrDefault(itemBase64, 0) + quantity);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load warehouse: " + e.getMessage());
        }
    }

    @Override
    public void addToWarehouse(String itemBase64, int quantity) {
        if (!this.prepareForOperation(true)) {
            return;
        }
        if (itemBase64 == null || quantity <= 0) {
            return;
        }
        this.warehouseCache.put(itemBase64, this.warehouseCache.getOrDefault(itemBase64, 0) + quantity);
        this.saveWarehouse();
    }

    @Override
    public int getWarehouseQuantity(String itemBase64) {
        if (!this.prepareForOperation(true)) {
            return 0;
        }
        return this.warehouseCache.getOrDefault(itemBase64, 0);
    }

    @Override
    public boolean takeFromWarehouse(String itemBase64, int quantity) {
        if (!this.prepareForOperation(true)) {
            return false;
        }
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
        this.saveWarehouse();
        return true;
    }

    @Override
    public Map<String, Integer> getWarehouseSnapshot() {
        if (!this.prepareForOperation(true)) {
            return new HashMap<String, Integer>();
        }
        return new HashMap<String, Integer>(this.warehouseCache);
    }

    private void saveWarehouse() {
        try {
            this.connection.createStatement().executeUpdate("DELETE FROM warehouse");
            String sql = "INSERT INTO warehouse (item_base64, quantity) VALUES (?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
                    if (entry.getValue() <= 0) continue;
                    ps.setString(1, entry.getKey());
                    ps.setInt(2, entry.getValue());
                    ps.executeUpdate();
                }
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to save warehouse: " + e.getMessage());
        }
    }

    private void loadAllMoneyWarehouse() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM money_warehouse");){
            while (rs.next()) {
                String playerUuid = rs.getString("player_uuid");
                BigDecimal amount = rs.getBigDecimal("amount");
                if (playerUuid == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                this.moneyWarehouseCache.put(playerUuid, this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO).add(amount));
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load money warehouse: " + e.getMessage());
        }
    }

    @Override
    public void addToMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (!this.prepareForOperation(true)) {
            return;
        }
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        this.moneyWarehouseCache.put(playerUuid, this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO).add(amount));
        this.saveMoneyWarehouse();
    }

    @Override
    public BigDecimal getMoneyWarehouseBalance(String playerUuid) {
        if (!this.prepareForOperation(true)) {
            return BigDecimal.ZERO;
        }
        return this.moneyWarehouseCache.getOrDefault(playerUuid, BigDecimal.ZERO);
    }

    @Override
    public boolean takeFromMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (!this.prepareForOperation(true)) {
            return false;
        }
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
        if (!this.prepareForOperation(true)) {
            return;
        }
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
        if (!this.prepareForOperation(true) || playerUuid == null || playerUuid.isEmpty()) {
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
        if (!this.prepareForOperation(true)) {
            return false;
        }
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

    @Override
    public int getDailyRegisterCount(String playerUuid, LocalDate date) {
        return 0;
    }

    @Override
    public void setDailyRegisterCount(String playerUuid, LocalDate date, int count) {
    }

    private void saveMoneyWarehouse() {
        try {
            this.connection.createStatement().executeUpdate("DELETE FROM money_warehouse");
            String sql = "INSERT INTO money_warehouse (player_uuid, amount) VALUES (?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                for (Map.Entry<String, BigDecimal> entry : this.moneyWarehouseCache.entrySet()) {
                    if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
                    ps.setString(1, entry.getKey());
                    ps.setBigDecimal(2, entry.getValue());
                    ps.executeUpdate();
                }
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to save money warehouse: " + e.getMessage());
        }
    }

    @Override
    public void withdrawWarehouse(Player player) {
        if (!this.prepareForOperation(true)) {
            player.sendMessage("\u00a7c\u6570\u636e\u5e93\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            return;
        }
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
        if (!this.prepareForOperation(true)) {
            player.sendMessage("\u00a7c\u6570\u636e\u5e93\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            return;
        }
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
        if (!this.prepareForOperation(true)) {
            player.sendMessage("\u00a7c\u6570\u636e\u5e93\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            return;
        }
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

    @Override
    public void shutdown() {
        try {
            if (this.connection != null && !this.connection.isClosed()) {
                this.connection.close();
                this.plugin.getLogger().info("MySQL connection closed.");
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Error closing MySQL connection: " + e.getMessage());
        }
    }
}
