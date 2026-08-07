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
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.ItemSerializer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
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
import java.util.logging.Level;
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
    private final Map<String, Map<String, Integer>> dailyRegisterLimitCache = new ConcurrentHashMap<String, Map<String, Integer>>();

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
            throw new IllegalStateException("MySQL JDBC driver not found");
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
            this.plugin.getLogger().log(Level.SEVERE, "MySQL connection exception", e);
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
            this.dailyRegisterLimitCache.clear();
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
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS exchange_items (  id INT PRIMARY KEY AUTO_INCREMENT,  material VARCHAR(64) NOT NULL,  nbt_hash VARCHAR(64) NOT NULL,  item_base64 TEXT NOT NULL,  display_name VARCHAR(256) NOT NULL,  item_name VARCHAR(256) DEFAULT '',  item_lore TEXT,  created_by_uuid VARCHAR(36),  created_by_name VARCHAR(32),  last_stocked_at BIGINT,  last_empty_at BIGINT,  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,  UNIQUE KEY uk_material_nbt (material, nbt_hash)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try {
                stmt.executeUpdate("ALTER TABLE exchange_items ADD COLUMN item_name VARCHAR(256) DEFAULT ''");
            }
            catch (SQLException sQLException) {
                // empty catch block
            }
            for (String migration : new String[]{
                "ALTER TABLE exchange_items ADD COLUMN created_by_uuid VARCHAR(36)",
                "ALTER TABLE exchange_items ADD COLUMN created_by_name VARCHAR(32)",
                "ALTER TABLE exchange_items ADD COLUMN last_stocked_at BIGINT",
                "ALTER TABLE exchange_items ADD COLUMN last_empty_at BIGINT"
            }) {
                try {
                    stmt.executeUpdate(migration);
                } catch (SQLException ignored) {
                    // The column already exists on migrated installations.
                }
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
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS daily_register_limits (  player_uuid VARCHAR(36) NOT NULL,  register_day DATE NOT NULL,  register_count INT NOT NULL DEFAULT 0,  PRIMARY KEY (player_uuid, register_day)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
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
        this.loadDailyRegisterLimits();
    }

    private void loadAllItems() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM exchange_items");){
            while (rs.next()) {
                ExchangeItem item = new ExchangeItem(rs.getInt("id"), rs.getString("material"), rs.getString("nbt_hash"), rs.getString("item_base64"), rs.getString("display_name"), rs.getTimestamp("created_at"));
                item.setItemName(rs.getString("item_name"));
                item.setItemLore(rs.getString("item_lore"));
                item.setCreatedByUuid(rs.getString("created_by_uuid"));
                item.setCreatedByName(rs.getString("created_by_name"));
                item.setLastStockedAt(this.readNullableTimestamp(rs, "last_stocked_at"));
                item.setLastEmptyAt(this.readNullableTimestamp(rs, "last_empty_at"));
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
        String sql = "INSERT INTO exchange_items (material, nbt_hash, item_base64, display_name, item_name, item_lore, created_by_uuid, created_by_name, last_stocked_at, last_empty_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql, 1);){
            ps.setString(1, item.getMaterial());
            ps.setString(2, item.getNbtHash());
            ps.setString(3, item.getItemBase64());
            ps.setString(4, item.getDisplayName());
            ps.setString(5, item.getItemName() != null ? item.getItemName() : item.getDisplayName());
            ps.setString(6, item.getItemLore() != null ? item.getItemLore() : "");
            ps.setString(7, item.getCreatedByUuid());
            ps.setString(8, item.getCreatedByName());
            this.setNullableTimestamp(ps, 9, item.getLastStockedAt());
            this.setNullableTimestamp(ps, 10, item.getLastEmptyAt());
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
        String sql = "UPDATE exchange_items SET material=?, nbt_hash=?, item_base64=?, display_name=?, item_name=?, item_lore=?, created_by_uuid=?, created_by_name=?, last_stocked_at=?, last_empty_at=? WHERE id=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setString(1, item.getMaterial());
            ps.setString(2, item.getNbtHash());
            ps.setString(3, item.getItemBase64());
            ps.setString(4, item.getDisplayName());
            ps.setString(5, item.getItemName() != null ? item.getItemName() : item.getDisplayName());
            ps.setString(6, item.getItemLore() != null ? item.getItemLore() : "");
            ps.setString(7, item.getCreatedByUuid());
            ps.setString(8, item.getCreatedByName());
            this.setNullableTimestamp(ps, 9, item.getLastStockedAt());
            this.setNullableTimestamp(ps, 10, item.getLastEmptyAt());
            ps.setInt(11, item.getId());
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
    public boolean updateOrder(Order order) {
        if (order == null || order.getId() <= 0) {
            return false;
        }
        if (!this.prepareForOperation(false)) {
            return false;
        }
        String sql = "UPDATE orders SET filled_qty=?, status=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, order.getFilledQty());
            ps.setString(2, order.getStatus().name());
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, order.getId());
            if (ps.executeUpdate() <= 0) {
                return false;
            }
            order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            this.orderCache.put(order.getId(), order);
            return true;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to update order " + order.getId() + ": " + e.getMessage());
            return false;
        }
    }

    private Timestamp readNullableTimestamp(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() || value <= 0L ? null : new Timestamp(value);
    }

    private void setNullableTimestamp(PreparedStatement statement, int index, Timestamp timestamp) throws SQLException {
        if (timestamp == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, timestamp.getTime());
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
    public long getLatestOrderCreatedAt(int itemId, Order.OrderType orderType) {
        if (!this.prepareForOperation(false)) {
            return 0L;
        }
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
    public boolean deleteTrade(int tradeId) {
        if (tradeId <= 0 || !this.prepareForOperation(false)) {
            return false;
        }
        Trade removed = this.tradeCache.get(tradeId);
        try (PreparedStatement ps = this.connection.prepareStatement("DELETE FROM trades WHERE id=?")) {
            ps.setInt(1, tradeId);
            if (ps.executeUpdate() <= 0) {
                return removed == null;
            }
            this.tradeCache.remove(tradeId);
            return true;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to delete trade " + tradeId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<Trade> getTradesByPlayer(String playerUuid, int limit, int offset) {
        if (playerUuid == null || limit <= 0) {
            return new ArrayList<Trade>();
        }
        int safeOffset = Math.max(0, offset);
        if (!this.prepareForOperation(true)) {
            return new ArrayList<Trade>();
        }
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
    public long getTradeVolumeSince(int itemId, long sinceMillis) {
        if (!this.prepareForOperation(false)) {
            return 0L;
        }
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
    public List<Trade> getTradesByItem(int itemId, int limit) {
        if (limit <= 0) {
            return new ArrayList<Trade>();
        }
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
    public boolean insertEscrow(EscrowEntry entry) {
        if (entry == null || entry.getOrderId() <= 0 || entry.getAssetType() == null) {
            return false;
        }
        if (!this.prepareForOperation(false)) {
            return false;
        }
        String sql = "REPLACE INTO escrow (order_id, player_uuid, asset_type, amount, item_base64, quantity) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, entry.getOrderId());
            ps.setString(2, entry.getPlayerUuid());
            ps.setString(3, entry.getAssetType().name());
            ps.setBigDecimal(4, entry.getAmount() != null ? entry.getAmount() : BigDecimal.ZERO);
            ps.setString(5, entry.getItemBase64() != null ? entry.getItemBase64() : "");
            ps.setInt(6, entry.getQuantity());
            if (ps.executeUpdate() <= 0) {
                return false;
            }
            this.escrowCache.put(this.escrowKey(entry.getOrderId(), entry.getAssetType()), entry);
            return true;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to insert escrow: " + e.getMessage());
            return false;
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
    public boolean deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        if (assetType == null) {
            return false;
        }
        if (!this.prepareForOperation(false)) {
            return false;
        }
        String sql = "DELETE FROM escrow WHERE order_id=? AND asset_type=?";
        try (PreparedStatement ps = this.connection.prepareStatement(sql);){
            ps.setInt(1, orderId);
            ps.setString(2, assetType.name());
            ps.executeUpdate();
            this.escrowCache.remove(this.escrowKey(orderId, assetType));
            return true;
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to delete escrow: " + e.getMessage());
            return false;
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
    public boolean addToWarehouse(String itemBase64, int quantity) {
        if (!this.prepareForOperation(true)) {
            return false;
        }
        if (itemBase64 == null || quantity <= 0) {
            return false;
        }
        int previous = this.warehouseCache.getOrDefault(itemBase64, 0);
        this.warehouseCache.put(itemBase64, previous + quantity);
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
        if (this.saveWarehouse()) {
            return true;
        }
        this.warehouseCache.put(itemBase64, current);
        return false;
    }

    @Override
    public Map<String, Integer> getWarehouseSnapshot() {
        if (!this.prepareForOperation(true)) {
            return new HashMap<String, Integer>();
        }
        HashMap<String, Integer> snapshot = new HashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
            if (entry.getKey().startsWith(PLAYER_WAREHOUSE_PREFIX) || entry.getValue() <= 0) {
                continue;
            }
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return snapshot;
    }

    private boolean saveWarehouse() {
        boolean originalAutoCommit = true;
        boolean stateRead = false;
        Savepoint savepoint = null;
        try {
            originalAutoCommit = this.connection.getAutoCommit();
            stateRead = true;
            if (originalAutoCommit) {
                this.connection.setAutoCommit(false);
            } else {
                savepoint = this.connection.setSavepoint();
            }
            try (Statement stmt = this.connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM warehouse");
            }
            String sql = "INSERT INTO warehouse (item_base64, quantity) VALUES (?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                for (Map.Entry<String, Integer> entry : this.warehouseCache.entrySet()) {
                    if (entry.getValue() <= 0) continue;
                    ps.setString(1, entry.getKey());
                    ps.setInt(2, entry.getValue());
                    ps.executeUpdate();
                }
            }
            if (originalAutoCommit) {
                this.connection.commit();
            }
            return true;
        }
        catch (SQLException e) {
            try {
                if (originalAutoCommit) {
                    this.connection.rollback();
                } else if (savepoint != null) {
                    this.connection.rollback(savepoint);
                }
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            this.plugin.getLogger().severe("Failed to save warehouse: " + e.getMessage());
            return false;
        }
        finally {
            if (stateRead && originalAutoCommit) {
                try {
                    this.connection.setAutoCommit(true);
                }
                catch (SQLException restoreException) {
                    this.plugin.getLogger().severe("Failed to restore MySQL auto-commit state: "
                        + restoreException.getMessage());
                }
            }
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

    private void loadDailyRegisterLimits() {
        try (Statement stmt = this.connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid, register_day, register_count FROM daily_register_limits");) {
            while (rs.next()) {
                String playerUuid = rs.getString("player_uuid");
                Date date = rs.getDate("register_day");
                if (playerUuid == null || date == null) {
                    continue;
                }
                this.dailyRegisterLimitCache
                    .computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<String, Integer>())
                    .put(date.toLocalDate().toString(), rs.getInt("register_count"));
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed to load daily register limits: " + e.getMessage());
        }
    }

    @Override
    public boolean addToMoneyWarehouse(String playerUuid, BigDecimal amount) {
        if (!this.prepareForOperation(true)) {
            return false;
        }
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
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
    public boolean addToPlayerItemWarehouse(String playerUuid, String itemBase64, int quantity) {
        if (!this.prepareForOperation(true)) {
            return false;
        }
        if (playerUuid == null || itemBase64 == null || quantity <= 0) {
            return false;
        }
        String key = this.getPlayerWarehouseKey(playerUuid, itemBase64);
        int previous = this.warehouseCache.getOrDefault(key, 0);
        this.warehouseCache.put(key, previous + quantity);
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
        if (this.saveWarehouse()) {
            return true;
        }
        this.warehouseCache.put(key, current);
        return false;
    }

    @Override
    public int getDailyRegisterCount(String playerUuid, LocalDate date) {
        if (!this.prepareForOperation(true) || playerUuid == null || date == null) {
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
        if (!this.prepareForOperation(false) || playerUuid == null || date == null || count < 0) {
            return;
        }
        String dateKey = date.toString();
        Map<String, Integer> previousCounts = this.dailyRegisterLimitCache.get(playerUuid);
        Map<String, Integer> snapshot = previousCounts == null
            ? null : new ConcurrentHashMap<String, Integer>(previousCounts);
        Map<String, Integer> counts = previousCounts != null
            ? previousCounts
            : this.dailyRegisterLimitCache.computeIfAbsent(
                playerUuid, ignored -> new ConcurrentHashMap<String, Integer>());
        if (count == 0) {
            counts.remove(dateKey);
        } else {
            counts.put(dateKey, count);
        }
        try {
            if (count == 0) {
                try (PreparedStatement ps = this.connection.prepareStatement(
                    "DELETE FROM daily_register_limits WHERE player_uuid=? AND register_day=?")) {
                    ps.setString(1, playerUuid);
                    ps.setDate(2, Date.valueOf(date));
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = this.connection.prepareStatement(
                    "INSERT INTO daily_register_limits (player_uuid, register_day, register_count) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE register_count=VALUES(register_count)")) {
                    ps.setString(1, playerUuid);
                    ps.setDate(2, Date.valueOf(date));
                    ps.setInt(3, count);
                    ps.executeUpdate();
                }
            }
            if (counts.isEmpty()) {
                this.dailyRegisterLimitCache.remove(playerUuid);
            }
        }
        catch (SQLException e) {
            if (snapshot == null || snapshot.isEmpty()) {
                this.dailyRegisterLimitCache.remove(playerUuid);
            } else {
                this.dailyRegisterLimitCache.put(playerUuid, snapshot);
            }
            this.plugin.getLogger().severe("Failed to save daily register limit: " + e.getMessage());
        }
    }

    private boolean saveMoneyWarehouse() {
        boolean originalAutoCommit = true;
        boolean stateRead = false;
        Savepoint savepoint = null;
        try {
            originalAutoCommit = this.connection.getAutoCommit();
            stateRead = true;
            if (originalAutoCommit) {
                this.connection.setAutoCommit(false);
            } else {
                savepoint = this.connection.setSavepoint();
            }
            try (Statement stmt = this.connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM money_warehouse");
            }
            String sql = "INSERT INTO money_warehouse (player_uuid, amount) VALUES (?, ?)";
            try (PreparedStatement ps = this.connection.prepareStatement(sql);){
                for (Map.Entry<String, BigDecimal> entry : this.moneyWarehouseCache.entrySet()) {
                    if (entry.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
                    ps.setString(1, entry.getKey());
                    ps.setBigDecimal(2, entry.getValue());
                    ps.executeUpdate();
                }
            }
            if (originalAutoCommit) {
                this.connection.commit();
            }
            return true;
        }
        catch (SQLException e) {
            try {
                if (originalAutoCommit) {
                    this.connection.rollback();
                } else if (savepoint != null) {
                    this.connection.rollback(savepoint);
                }
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            this.plugin.getLogger().severe("Failed to save money warehouse: " + e.getMessage());
            return false;
        }
        finally {
            if (stateRead && originalAutoCommit) {
                try {
                    this.connection.setAutoCommit(true);
                }
                catch (SQLException restoreException) {
                    this.plugin.getLogger().severe("Failed to restore MySQL auto-commit state: "
                        + restoreException.getMessage());
                }
            }
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
        if (!this.prepareForOperation(true)) {
            player.sendMessage("\u00a7c\u6570\u636e\u5e93\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
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
        if (player == null || !this.prepareForOperation(true)) {
            if (player != null) {
                player.sendMessage("\u00a7c\u6570\u636e\u5e93\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            }
            return;
        }
        if (itemBase64 == null || itemBase64.isEmpty()) {
            player.sendMessage("\u00a7c\u65e0\u6548\u7684\u4ed3\u5e93\u7269\u54c1\u3002");
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
