/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.PluginCommand
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.event.Listener
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.github.exchange;

import com.github.exchange.adapter.Adapter_1_11_R1;
import com.github.exchange.adapter.Adapter_1_12_R1;
import com.github.exchange.adapter.Adapter_1_16_R3;
import com.github.exchange.adapter.Adapter_1_18_R2;
import com.github.exchange.adapter.Adapter_1_20_R1;
import com.github.exchange.adapter.VersionAdapter;
import com.github.exchange.command.ChatInputHandler;
import com.github.exchange.command.ExchangeCommand;
import com.github.exchange.gui.ExchangeGUI;
import com.github.exchange.listener.SettlementDeliveryListener;
import com.github.exchange.manager.EscrowManager;
import com.github.exchange.manager.ItemManager;
import com.github.exchange.manager.OrderManager;
import com.github.exchange.manager.SellBuyerTracker;
import com.github.exchange.manager.TradeManager;
import com.github.exchange.storage.FileStorageManager;
import com.github.exchange.storage.MySQLStorageManager;
import com.github.exchange.storage.StorageManager;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.ItemDatabase;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.TaxCalculator;
import com.github.exchange.web.WebMarketManager;
import java.math.BigDecimal;
import java.io.File;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import cn.gmzc.mail.MailService;

public class StockExchangePlugin
extends JavaPlugin {
    private static StockExchangePlugin instance;
    private Economy economy;
    private VersionAdapter versionAdapter;
    private StorageManager storageManager;
    private ItemManager itemManager;
    private OrderManager orderManager;
    private TradeManager tradeManager;
    private EscrowManager escrowManager;
    private SellBuyerTracker sellBuyerTracker;
    private ChatInputHandler chatInputHandler;
    private ItemDatabase itemDatabase;
    private boolean storageAvailable = true;
    private double priceTick;
    private double minPrice;
    private double maxPrice;
    private int maxOrderQuantity;
    private int orderExpireDays;
    private boolean ignoreDurability;
    private List<String> ignoreTags;
    private BigDecimal taxRatePercent;
    private String systemAccount;
    private boolean priceLimitEnabled;
    private double limitUpPercent;
    private double limitDownPercent;
    private int guiUpdateIntervalTicks;
    private int dailyRegisterLimit;
    private BigDecimal diamondToMoneyAmount;
    private Material diamondMaterial;
    private String currencyName;
    private BukkitTask marketCleanupTask;
    private final List<String> announcements = new ArrayList<String>();
    private MailService mailService;
    private WebMarketManager webMarketManager;


    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.loadConfigValues();
        if (!this.setupEconomy()) {
            this.getLogger().severe("Vault economy not found! Disabling plugin.");
            this.getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.initVersionAdapter();
        String dbType = this.getConfig().getString("database.type", "FILE").toUpperCase();
        this.storageManager = dbType.equals("MYSQL") ? new MySQLStorageManager(this) : new FileStorageManager(this);
        try {
            this.storageManager.init();
            this.storageAvailable = true;
        }
        catch (Exception e) {
            this.storageAvailable = false;
            this.getLogger().severe("Storage initialization failed, plugin will continue in limited mode.");
            this.getLogger().severe("[stockexchange]\u6570\u636e\u5e93\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\uff01");
            this.getLogger().log(Level.SEVERE, "Storage initialization exception", e);
        }
        this.itemManager = new ItemManager(this);
        this.itemManager.normalizeCatalogDisplayNames();
        this.itemManager.ensureSpecialCategories();
        this.orderManager = new OrderManager(this);
        this.tradeManager = new TradeManager(this);
        this.escrowManager = new EscrowManager(this);
        this.sellBuyerTracker = new SellBuyerTracker(
            new File(this.getDataFolder(), "sell-buyers.yml"),
            this.getLogger()
        );
        this.sellBuyerTracker.load();
        this.chatInputHandler = new ChatInputHandler(this);
        this.itemDatabase = new ItemDatabase(this.getLogger());
        this.webMarketManager = new WebMarketManager(this);
        ExchangeCommand exchangeCmd = new ExchangeCommand(this);
        PluginCommand seCommand = this.getCommand("se");
        if (seCommand != null) {
            seCommand.setExecutor((CommandExecutor)exchangeCmd);
            seCommand.setTabCompleter((TabCompleter)exchangeCmd);
            this.getLogger().info("[CommandBind] /se executor and tab completer bound to ExchangeCommand");
        } else {
            this.getLogger().severe("[CommandBind] /se command not found in plugin.yml!");
        }
        PluginCommand exchangeCommand = this.getCommand("exchange");
        if (exchangeCommand != null) {
            exchangeCommand.setExecutor((CommandExecutor)exchangeCmd);
            exchangeCommand.setTabCompleter((TabCompleter)exchangeCmd);
            this.getLogger().info("[CommandBind] /exchange executor and tab completer bound to ExchangeCommand");
        } else {
            this.getLogger().warning("[CommandBind] /exchange command not found in plugin.yml (will rely on alias if present)");
        }
        this.getServer().getPluginManager().registerEvents((Listener)this.chatInputHandler, (Plugin)this);
        this.chatInputHandler.registerEvents();
        this.getServer().getPluginManager().registerEvents((Listener)new ExchangeGUI(), (Plugin)this);
        this.getServer().getPluginManager().registerEvents((Listener)new SettlementDeliveryListener(this), (Plugin)this);
        this.startCleanupTask();
        this.getLogger().info("StockExchange v" + this.getDescription().getVersion() + " enabled!");
    }

    public void onDisable() {
        if (this.marketCleanupTask != null) {
            this.marketCleanupTask.cancel();
            this.marketCleanupTask = null;
        }
        if (this.storageManager != null) {
            this.storageManager.shutdown();
        }
        if (this.sellBuyerTracker != null) {
            this.sellBuyerTracker.save();
        }
        this.getLogger().info("StockExchange disabled!");
    }

    private boolean setupEconomy() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.economy = (Economy)rsp.getProvider();
        EconomyUtil.init(this.economy);
        return true;
    }

    private void initVersionAdapter() {
        String version = this.getServer().getBukkitVersion();
        this.getLogger().info("Detected server version: " + version);
        if (version.contains("1.11")) {
            this.versionAdapter = new Adapter_1_11_R1();
        } else if (version.contains("1.12")) {
            this.versionAdapter = new Adapter_1_12_R1();
        } else if (version.contains("1.16")) {
            this.versionAdapter = new Adapter_1_16_R3();
        } else if (version.contains("1.18")) {
            this.versionAdapter = new Adapter_1_18_R2();
        } else if (version.contains("1.20") || version.contains("1.21")) {
            this.versionAdapter = new Adapter_1_20_R1();
        } else {
            this.getLogger().warning("Unsupported server version, using 1.20 adapter as fallback.");
            this.versionAdapter = new Adapter_1_20_R1();
        }
    }

    public void loadConfigValues() {
        this.reloadConfig();
        boolean migrateLegacyFees = this.getConfig().isConfigurationSection("fees");
        this.priceTick = this.positiveFiniteConfig("trading.price_tick", 0.01);
        this.minPrice = this.positiveFiniteConfig("trading.min_price", 0.01);
        this.maxPrice = Math.max(this.minPrice, this.positiveFiniteConfig("trading.max_price", 9.999999999E7));
        this.maxOrderQuantity = Math.max(1, this.getConfig().getInt("trading.max_order_quantity", 2304));
        this.orderExpireDays = Math.max(1, this.getConfig().getInt("trading.order_expire_days", 30));
        this.ignoreDurability = this.getConfig().getBoolean("trading.ignore_durability", false);
        this.ignoreTags = this.getConfig().getStringList("trading.ignore_tags");
        BigDecimal configuredTaxRate = BigDecimal.valueOf(this.finiteConfig("tax.rate_percent", 10.0));
        this.taxRatePercent = TaxCalculator.normalizePercent(configuredTaxRate);
        if (this.taxRatePercent.compareTo(configuredTaxRate) != 0) {
            this.getLogger().warning("tax.rate_percent must be between 0 and 100; using " + this.taxRatePercent.toPlainString());
        }
        this.systemAccount = migrateLegacyFees
            ? this.getConfig().getString("fees.system_account", "")
            : this.getConfig().getString("tax.system_account", "");
        if (migrateLegacyFees) {
            this.getConfig().set("tax.rate_percent", this.taxRatePercent.doubleValue());
            this.getConfig().set("tax.system_account", this.systemAccount);
            this.getConfig().set("fees", null);
            this.saveConfig();
        }
        this.priceLimitEnabled = this.getConfig().getBoolean("price_limit.enabled", false);
        this.limitUpPercent = Math.max(0.0, this.finiteConfig("price_limit.limit_up_percent", 10.0));
        this.limitDownPercent = Math.max(0.0, this.finiteConfig("price_limit.limit_down_percent", 10.0));
        this.guiUpdateIntervalTicks = Math.max(1, this.getConfig().getInt("gui.update_interval_ticks", 20));
        this.dailyRegisterLimit = Math.max(0, this.getConfig().getInt("market.daily_register_limit", 5));
        this.diamondToMoneyAmount = BigDecimal.valueOf(this.positiveFiniteConfig("market.diamond_to_money_amount", 1000.0));
        this.currencyName = this.getConfig().getString("market.currency_name", "\u661f\u5149\u70b9");
        this.diamondMaterial = Material.matchMaterial(this.getConfig().getString("market.diamond_material", "DIAMOND"));
        if (this.diamondMaterial == null) {
            this.diamondMaterial = Material.DIAMOND;
        }
        this.announcements.clear();
        this.announcements.addAll(this.getConfig().getStringList("announcements"));
    }

    private double finiteConfig(String path, double fallback) {
        double value = this.getConfig().getDouble(path, fallback);
        if (Double.isFinite(value)) {
            return value;
        }
        this.getLogger().warning(path + " must be finite; using " + fallback);
        return fallback;
    }

    private double positiveFiniteConfig(String path, double fallback) {
        double value = this.finiteConfig(path, fallback);
        if (value > 0.0) {
            return value;
        }
        this.getLogger().warning(path + " must be positive; using " + fallback);
        return fallback;
    }

    private void startCleanupTask() {
        if (this.marketCleanupTask != null) {
            this.marketCleanupTask.cancel();
        }
        this.marketCleanupTask = this.getServer().getScheduler().runTaskTimer(this, () -> {
            if (this.itemManager != null) {
                this.itemManager.cleanupExpiredEmptyItems();
            }
        }, 20L * 60L, 20L * 60L);
    }

    public synchronized List<String> getAnnouncements() {
        return new ArrayList<String>(this.announcements);
    }

    public synchronized int addAnnouncement(String content) {
        this.announcements.add(content);
        this.saveAnnouncements();
        return this.announcements.size();
    }

    public synchronized boolean editAnnouncement(int id, String content) {
        int idx = id - 1;
        if (idx < 0 || idx >= this.announcements.size()) {
            return false;
        }
        this.announcements.set(idx, content);
        this.saveAnnouncements();
        return true;
    }

    public synchronized boolean deleteAnnouncement(int id) {
        int idx = id - 1;
        if (idx < 0 || idx >= this.announcements.size()) {
            return false;
        }
        this.announcements.remove(idx);
        this.saveAnnouncements();
        return true;
    }

    private synchronized void saveAnnouncements() {
        this.getConfig().set("announcements", new ArrayList<String>(this.announcements));
        this.saveConfig();
    }

    public synchronized boolean reconnectStorage() {
        String dbType = this.getConfig().getString("database.type", "FILE").toUpperCase(Locale.ROOT);
        try {
            if (this.storageManager != null) {
                this.storageManager.shutdown();
            }
            this.storageManager = dbType.equals("MYSQL") ? new MySQLStorageManager(this) : new FileStorageManager(this);
            this.storageManager.init();
            this.storageAvailable = true;
            return true;
        }
        catch (Exception e) {
            this.storageAvailable = false;
            this.getLogger().severe("Storage reconnect failed, plugin is in limited mode.");
            this.getLogger().log(Level.SEVERE, "Storage reconnect exception", e);
            return false;
        }
    }

    public static StockExchangePlugin getInstance() {
        return instance;
    }

    public Economy getEconomy() {
        return this.economy;
    }

    public VersionAdapter getVersionAdapter() {
        return this.versionAdapter;
    }

    public StorageManager getStorageManager() {
        return this.storageManager;
    }

    public boolean isStorageAvailable() {
        return this.storageAvailable;
    }

    public ItemManager getItemManager() {
        return this.itemManager;
    }

    public OrderManager getOrderManager() {
        return this.orderManager;
    }

    public TradeManager getTradeManager() {
        return this.tradeManager;
    }

    public EscrowManager getEscrowManager() {
        return this.escrowManager;
    }

    public SellBuyerTracker getSellBuyerTracker() {
        return this.sellBuyerTracker;
    }

    public ChatInputHandler getChatInputHandler() {
        return this.chatInputHandler;
    }

    public ItemDatabase getItemDatabase() {
        return this.itemDatabase;
    }

    public WebMarketManager getWebMarketManager() {
        return this.webMarketManager;
    }

    public double getPriceTick() {
        return this.priceTick;
    }

    public double getMinPrice() {
        return this.minPrice;
    }

    public double getMaxPrice() {
        return this.maxPrice;
    }

    public int getMaxOrderQuantity() {
        return this.maxOrderQuantity;
    }

    public int getOrderExpireDays() {
        return this.orderExpireDays;
    }

    public boolean isIgnoreDurability() {
        return this.ignoreDurability;
    }

    public List<String> getIgnoreTags() {
        return this.ignoreTags;
    }

    public BigDecimal getTaxRatePercent() {
        return this.taxRatePercent;
    }

    public synchronized boolean setTaxRatePercent(BigDecimal percent) {
        if (percent == null
            || percent.compareTo(BigDecimal.ZERO) < 0
            || percent.compareTo(BigDecimal.valueOf(100L)) > 0) {
            return false;
        }
        this.taxRatePercent = percent.stripTrailingZeros();
        this.getConfig().set("tax.rate_percent", this.taxRatePercent.doubleValue());
        this.saveConfig();
        return true;
    }

    public String getSystemAccount() {
        return this.systemAccount;
    }

    public boolean collectTax(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (this.systemAccount == null || this.systemAccount.isBlank()) {
            return true;
        }
        try {
            boolean deposited = EconomyUtil.deposit(java.util.UUID.fromString(this.systemAccount), amount);
            if (!deposited) {
                this.getLogger().warning("Failed to deposit tax " + amount.toPlainString() + " to system account.");
            }
            return deposited;
        } catch (IllegalArgumentException ex) {
            this.getLogger().warning("Invalid tax.system_account UUID: " + this.systemAccount);
            return false;
        }
    }

    public boolean isPriceLimitEnabled() {
        return this.priceLimitEnabled;
    }

    public double getLimitUpPercent() {
        return this.limitUpPercent;
    }

    public double getLimitDownPercent() {
        return this.limitDownPercent;
    }

    public int getGuiUpdateIntervalTicks() {
        return this.guiUpdateIntervalTicks;
    }

    public int getDailyRegisterLimit() {
        return this.dailyRegisterLimit;
    }

    public BigDecimal getDiamondToMoneyAmount() {
        return this.diamondToMoneyAmount;
    }

    public Material getDiamondMaterial() {
        return this.diamondMaterial;
    }

    public String getCurrencyName() {
        return this.currencyName;
    }

    public boolean isGrowthAccessRestricted(Player player) {
        return GrowthLevelAccess.restricted(player);
    }

    public boolean isGrowthAccessRestricted(String playerUuid) {
        try {
            return GrowthLevelAccess.restricted(
                playerUuid == null ? null : UUID.fromString(playerUuid)
            );
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    public boolean denyGrowthAccess(Player player) {
        if (!this.isGrowthAccessRestricted(player)) {
            return false;
        }
        player.sendMessage(this.growthAccessMessage(player));
        return true;
    }

    public String growthAccessMessage(Player player) {
        return "\u00a7c\u4ea4\u6613\u5e02\u573a\u529f\u80fd\u9700\u8981\u6210\u957f\u7b49\u7ea7\u8fbe\u5230 \u00a7e"
            + GrowthLevelAccess.REQUIRED_LEVEL
            + "\u00a7c \u7ea7\u540e\u624d\u80fd\u4f7f\u7528\u3002\u5f53\u524d\u7b49\u7ea7\uff1a\u00a7f"
            + GrowthLevelAccess.level(player);
    }

    public String growthAccessMessage(String playerUuid) {
        int level = 0;
        try {
            level = GrowthLevelAccess.level(
                playerUuid == null ? null : UUID.fromString(playerUuid)
            );
        } catch (IllegalArgumentException ignored) {
            level = 0;
        }
        return "\u00a7c\u4ea4\u6613\u5e02\u573a\u529f\u80fd\u9700\u8981\u6210\u957f\u7b49\u7ea7\u8fbe\u5230 \u00a7e"
            + GrowthLevelAccess.REQUIRED_LEVEL
            + "\u00a7c \u7ea7\u540e\u624d\u80fd\u4f7f\u7528\u3002\u5f53\u524d\u7b49\u7ea7\uff1a\u00a7f"
            + level;
    }

    public boolean isBedrockPlayer(Player player) {
        if (player == null) {
            return false;
        }
        PluginManager pluginManager = this.getServer().getPluginManager();
        Plugin floodgate = pluginManager.getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        }
        catch (Throwable throwable) {
            this.getLogger().warning("Failed to query Floodgate player state: " + throwable.getMessage());
            return false;
        }
    }

    public String exchangeDiamondForMoney(Player player) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u8fdb\u884c\u5151\u6362\u3002";
        }
        if (this.isGrowthAccessRestricted(player)) {
            return this.growthAccessMessage(player);
        }
        BigDecimal tax = TaxCalculator.tax(this.diamondToMoneyAmount, this.taxRatePercent);
        BigDecimal received = TaxCalculator.afterTax(this.diamondToMoneyAmount, this.taxRatePercent);
        ItemStack removedDiamond = this.removeSingleDiamond(player);
        if (removedDiamond == null) {
            return "\u00a7c\u4f60\u8eab\u4e0a\u6ca1\u6709\u53ef\u7528\u4e8e\u5151\u6362\u7684\u94bb\u77f3\u3002";
        }
        if (received.compareTo(BigDecimal.ZERO) > 0 && !EconomyUtil.deposit(player.getUniqueId(), received)) {
            if (this.returnItem(player, removedDiamond)) {
                return "\u00a7c\u5165\u8d26\u5931\u8d25\uff0c\u94bb\u77f3\u5df2\u9000\u56de\u3002";
            }
            this.getLogger().severe("[AssetAudit] DIAMOND_REFUND_FAILED player=" + player.getUniqueId());
            return "\u00a7c\u5165\u8d26\u548c\u94bb\u77f3\u9000\u56de\u5747\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
        }
        this.collectTax(tax);
        return "\u00a7a\u5151\u6362\u6210\u529f\uff1a1 \u94bb\u77f3 -> "
            + received.toPlainString() + " " + this.currencyName
            + "\u00a77\uff08\u7a0e\u989d " + tax.toPlainString() + "\uff0c\u7a0e\u7387 "
            + this.taxRatePercent.toPlainString() + "%\uff09";
    }

    public String exchangeMoneyForDiamond(Player player) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u8fdb\u884c\u5151\u6362\u3002";
        }
        if (this.isGrowthAccessRestricted(player)) {
            return this.growthAccessMessage(player);
        }
        BigDecimal tax = TaxCalculator.tax(this.diamondToMoneyAmount, this.taxRatePercent);
        BigDecimal totalCost = TaxCalculator.withTax(this.diamondToMoneyAmount, this.taxRatePercent);
        if (!EconomyUtil.hasBalance(player.getUniqueId(), totalCost)) {
            return "\u00a7c\u4f59\u989d\u4e0d\u8db3\uff0c\u9700\u8981 " + totalCost.toPlainString() + " " + this.currencyName
                + "\uff08\u542b\u7a0e " + tax.toPlainString() + "\uff09\u3002";
        }
        if (!EconomyUtil.withdraw(player.getUniqueId(), totalCost)) {
            return "\u00a7c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        ItemStack diamond = new ItemStack(this.diamondMaterial, 1);
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(diamond);
        if (!leftovers.isEmpty()) {
            if (EconomyUtil.deposit(player.getUniqueId(), totalCost)) {
                return "\u00a7c\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u661f\u5149\u70b9\u5df2\u9000\u56de\uff0c\u8bf7\u5148\u6e05\u7406\u80cc\u5305\u3002";
            }
            if (this.storageManager.addToMoneyWarehouse(player.getUniqueId().toString(), totalCost)) {
                return "\u00a7e\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u9000\u6b3e\u5df2\u5b58\u5165\u4ea4\u6613\u4ed3\u5e93\u3002";
            }
            String itemBase64 = ItemSerializer.itemToBase64(diamond);
            if (itemBase64 != null && this.storageManager.addToPlayerItemWarehouse(
                player.getUniqueId().toString(), itemBase64, 1)) {
                this.collectTax(tax);
                return "\u00a7e\u80cc\u5305\u7a7a\u95f4\u4e0d\u8db3\uff0c\u94bb\u77f3\u5df2\u5b58\u5165\u4ea4\u6613\u4ed3\u5e93\u3002";
            }
            this.getLogger().severe("[AssetAudit] MONEY_TO_DIAMOND_REFUND_FAILED player="
                + player.getUniqueId() + " amount=" + totalCost);
            return "\u00a7c\u9000\u6b3e\u548c\u94bb\u77f3\u4ed3\u5e93\u4fdd\u5b58\u5747\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
        }
        this.collectTax(tax);
        return "\u00a7a\u5151\u6362\u6210\u529f\uff1a" + totalCost.toPlainString() + " " + this.currencyName
            + " -> 1 \u94bb\u77f3\u00a77\uff08\u7a0e\u989d " + tax.toPlainString() + "\uff0c\u7a0e\u7387 "
            + this.taxRatePercent.toPlainString() + "%\uff09";
    }

    private ItemStack removeSingleDiamond(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; ++i) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != this.diamondMaterial || stack.getAmount() <= 0) {
                continue;
            }
            ItemStack removed = stack.clone();
            removed.setAmount(1);
            stack.setAmount(stack.getAmount() - 1);
            contents[i] = stack.getAmount() <= 0 ? null : stack;
            player.getInventory().setContents(contents);
            return removed;
        }
        return null;
    }

    private boolean returnItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            String itemBase64 = ItemSerializer.itemToBase64(leftover);
            if (itemBase64 == null || !this.storageManager.addToPlayerItemWarehouse(
                player.getUniqueId().toString(), itemBase64, leftover.getAmount())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the GMZCMail service if available.
     */
    public MailService getMailService() {
        if (this.mailService != null) {
            return this.mailService;
        }
        Plugin mailPlugin = this.getServer().getPluginManager().getPlugin("GMZCMail");
        if (mailPlugin == null || !mailPlugin.isEnabled()) {
            return null;
        }
        try {
            RegisteredServiceProvider<MailService> registration =
                this.getServer().getServicesManager().getRegistration(MailService.class);
            if (registration != null) {
                this.mailService = registration.getProvider();
            }
        } catch (Throwable t) {
            this.getLogger().warning("Failed to get MailService: " + t.getMessage());
        }
        return this.mailService;
    }

    /**
     * Sends items to a player via GMZCMail if available.
     * Must be called from the main thread.
     *
     * @return true if the items were mailed successfully, false otherwise
     */
    public boolean sendItemsAsMail(Player player, String displayName, ItemStack items) {
        if (player == null || items == null || items.getAmount() <= 0) {
            return false;
        }
        MailService mail = this.getMailService();
        if (mail == null) {
            return false;
        }
        try {
            mail.sendSystemMail(
                "\u00a76\u4ea4\u6613\u6240\u7cfb\u7edf",
                player.getUniqueId(),
                player.getName(),
                "\u00a7a\u60a8\u7684\u80cc\u5305\u5df2\u6ee1\uff0c\u7269\u54c1 "
                    + displayName + " x" + items.getAmount() + " \u5df2\u8f6c\u4e3a\u90ae\u4ef6\u53d1\u653e\u3002",
                Collections.singletonList(items.clone())
            );
            return true;
        } catch (Throwable t) {
            this.getLogger().warning("Failed to send items via mail: " + t.getMessage());
            return false;
        }
    }
}
