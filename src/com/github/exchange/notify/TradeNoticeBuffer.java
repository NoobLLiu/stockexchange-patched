package com.github.exchange.notify;

import com.github.exchange.StockExchangePlugin;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 交易提醒合并缓冲：手动提醒 5 秒合并；被动成交提醒 5 秒滚动合并（每次新事件续期，
 * 持续 5 秒无新事件才打包发送），售出/到货按（买家+物品）/物品累加数量与金额。
 * 离线期间被动提醒与手动提醒持久化到 offline-notices.yml，玩家上线后合并发送。
 */
public class TradeNoticeBuffer {
    public enum Type {
        MANUAL,
        PASSIVE
    }

    private final StockExchangePlugin plugin;
    private final long manualDelayTicks;
    private final long passiveDelayTicks;
    private final Map<UUID, OperationNoticeAggregator> manualOnline =
        new HashMap<UUID, OperationNoticeAggregator>();
    private final Map<UUID, PassiveNoticeAggregator> passiveOnline =
        new HashMap<UUID, PassiveNoticeAggregator>();
    private final Map<UUID, PassiveNoticeAggregator> passiveOffline =
        new HashMap<UUID, PassiveNoticeAggregator>();
    private final Map<String, BukkitTask> pendingTasks = new HashMap<String, BukkitTask>();
    private final Set<String> pendingListingNames = new LinkedHashSet<String>();
    private BukkitTask pendingListingTask;
    private final File offlineFile;

    public TradeNoticeBuffer(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.manualDelayTicks = Math.max(1L,
            plugin.getConfig().getInt("notify.manual_delay_seconds", 5) * 20L);
        this.passiveDelayTicks = Math.max(1L,
            plugin.getConfig().getInt("notify.passive_delay_seconds", 5) * 20L);
        this.offlineFile = new File(plugin.getDataFolder(), "offline-notices.yml");
        this.loadOffline();
    }

    public void manual(Player player, String message) {
        if (player != null) {
            this.manual(player.getUniqueId(), message);
        }
    }

    public void manual(UUID uuid, String message) {
        if (uuid == null || message == null || message.isBlank()) {
            return;
        }
        synchronized (this) {
            if (isStructuredOperationEcho(message)) {
                return;
            }
            this.manualOnline.computeIfAbsent(
                uuid, key -> new OperationNoticeAggregator()
            ).addLegacy(message);
            this.reschedule(uuid, Type.MANUAL);
        }
    }

    public void purchased(
        Player player,
        String itemName,
        int quantity,
        BigDecimal gross,
        BigDecimal tax,
        BigDecimal charged
    ) {
        if (player == null) {
            return;
        }
        synchronized (this) {
            this.manualOnline.computeIfAbsent(
                player.getUniqueId(), key -> new OperationNoticeAggregator()
            ).addPurchase(itemName, quantity, gross, tax, charged);
            this.reschedule(player.getUniqueId(), Type.MANUAL);
        }
    }

    public void sold(
        Player player,
        String itemName,
        int quantity,
        BigDecimal gross,
        BigDecimal tax,
        BigDecimal received
    ) {
        if (player == null) {
            return;
        }
        synchronized (this) {
            this.manualOnline.computeIfAbsent(
                player.getUniqueId(), key -> new OperationNoticeAggregator()
            ).addSale(itemName, quantity, gross, tax, received);
            this.reschedule(player.getUniqueId(), Type.MANUAL);
        }
    }

    public void listed(Player player, String itemName, int quantity) {
        if (player == null) {
            return;
        }
        synchronized (this) {
            this.manualOnline.computeIfAbsent(
                player.getUniqueId(), key -> new OperationNoticeAggregator()
            ).addListing(itemName, quantity);
            this.reschedule(player.getUniqueId(), Type.MANUAL);
        }
    }

    public synchronized void broadcastListing(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return;
        }
        this.pendingListingNames.add(itemName);
        if (this.pendingListingTask != null) {
            this.pendingListingTask.cancel();
        }
        this.pendingListingTask = this.plugin.getServer().getScheduler().runTaskLater(
            this.plugin,
            this::flushListingBroadcast,
            this.manualDelayTicks
        );
    }

    public void passiveSold(UUID sellerUuid, UUID buyerUuid, String itemName, int quantity,
                            BigDecimal amount) {
        if (sellerUuid == null || itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        String buyerKey = buyerUuid == null ? "?" : buyerUuid.toString();
        this.queuePassive(sellerUuid,
            aggregator -> aggregator.addSold(buyerKey, itemName, quantity, amount));
    }

    public void passiveArrived(UUID buyerUuid, String itemName, int quantity) {
        if (buyerUuid == null || itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        this.queuePassive(buyerUuid, aggregator -> aggregator.addArrived(itemName, quantity));
    }

    private synchronized void queuePassive(UUID uuid, Consumer<PassiveNoticeAggregator> add) {
        if (Bukkit.getPlayer(uuid) == null) {
            PassiveNoticeAggregator offline = this.passiveOffline.computeIfAbsent(
                uuid, key -> new PassiveNoticeAggregator());
            add.accept(offline);
            this.saveOffline();
            return;
        }
        PassiveNoticeAggregator online = this.passiveOnline.computeIfAbsent(
            uuid, key -> new PassiveNoticeAggregator());
        add.accept(online);
        this.reschedule(uuid, Type.PASSIVE);
    }

    /** 每次新消息都取消旧任务并重新计时，实现“持续有新事件则一直延迟”的滚动窗口。 */
    private synchronized void reschedule(UUID uuid, Type type) {
        String key = uuid + ":" + type.name();
        BukkitTask old = this.pendingTasks.remove(key);
        if (old != null) {
            old.cancel();
        }
        long delay = type == Type.MANUAL ? this.manualDelayTicks : this.passiveDelayTicks;
        UUID target = uuid;
        Type targetType = type;
        this.pendingTasks.put(key, this.plugin.getServer().getScheduler()
            .runTaskLater(this.plugin, () -> this.flush(target, targetType), delay));
    }

    private synchronized void flush(UUID uuid, Type type) {
        this.pendingTasks.remove(uuid + ":" + type.name());
        if (type == Type.MANUAL) {
            OperationNoticeAggregator queued = this.manualOnline.remove(uuid);
            if (queued != null && !queued.isEmpty()) {
                this.deliverLines(
                    uuid,
                    this.header(type),
                    queued.buildLines(this.plugin.getCurrencyName())
                );
            }
            return;
        }
        PassiveNoticeAggregator aggregator = this.passiveOnline.remove(uuid);
        if (aggregator == null || aggregator.isEmpty()) {
            return;
        }
        List<String> lines = aggregator.buildLines(this.plugin.getCurrencyName());
        if (!lines.isEmpty()) {
            this.deliverLines(uuid, this.header(type), lines);
        }
    }

    private void deliverLines(UUID uuid, String header, List<String> lines) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            this.sendGrouped(player, header, lines);
            return;
        }
        PassiveNoticeAggregator offline = this.passiveOffline.computeIfAbsent(
            uuid, key -> new PassiveNoticeAggregator());
        for (String line : lines) {
            offline.addLegacy(line);
        }
        this.saveOffline();
    }

    /** 插件关闭前冲刷所有在线缓冲，离线玩家的积攒写入持久化。 */
    public synchronized void flushAll() {
        for (BukkitTask task : this.pendingTasks.values()) {
            task.cancel();
        }
        this.pendingTasks.clear();
        if (this.pendingListingTask != null) {
            this.pendingListingTask.cancel();
            this.pendingListingTask = null;
        }
        this.flushListingBroadcast();
        for (Map.Entry<UUID, OperationNoticeAggregator> entry :
            new ArrayList<Map.Entry<UUID, OperationNoticeAggregator>>(this.manualOnline.entrySet())) {
            this.manualOnline.remove(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                this.deliverLines(
                    entry.getKey(),
                    this.header(Type.MANUAL),
                    entry.getValue().buildLines(this.plugin.getCurrencyName())
                );
            }
        }
        for (Map.Entry<UUID, PassiveNoticeAggregator> entry :
            new ArrayList<Map.Entry<UUID, PassiveNoticeAggregator>>(this.passiveOnline.entrySet())) {
            this.passiveOnline.remove(entry.getKey());
            List<String> lines = entry.getValue().buildLines(this.plugin.getCurrencyName());
            if (!lines.isEmpty()) {
                this.deliverLines(entry.getKey(), this.header(Type.PASSIVE), lines);
            }
        }
    }

    /** 玩家上线：把离线期间积攒的交易提醒合并发送并清理。 */
    public void flushOffline(Player player) {
        if (player == null) {
            return;
        }
        PassiveNoticeAggregator aggregator;
        synchronized (this) {
            aggregator = this.passiveOffline.remove(player.getUniqueId());
            if (aggregator != null && !aggregator.isEmpty()) {
                this.saveOffline();
            }
        }
        if (aggregator == null || aggregator.isEmpty() || !player.isOnline()) {
            return;
        }
        List<String> lines = aggregator.buildLines(this.plugin.getCurrencyName());
        if (!lines.isEmpty()) {
            this.sendGrouped(player, "\u00a7e[\u4ea4\u6613\u5e02\u573a\u00b7\u79bb\u7ebf\u4ea4\u6613] \u00a77", lines);
        }
    }

    private String header(Type type) {
        return type == Type.MANUAL
            ? "\u00a7e[\u4ea4\u6613\u5e02\u573a\u00b7\u64cd\u4f5c] \u00a77"
            : "\u00a7e[\u4ea4\u6613\u5e02\u573a\u00b7\u6210\u4ea4] \u00a77";
    }

    private void sendGrouped(Player player, String header, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder(header);
        builder.append(lines.get(0));
        for (int i = 1; i < lines.size(); ++i) {
            builder.append("\n").append(lines.get(i));
        }
        player.sendMessage(builder.toString());
    }

    private synchronized void flushListingBroadcast() {
        this.pendingListingTask = null;
        if (this.pendingListingNames.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder("§6新的商品：§f");
        boolean first = true;
        for (String itemName : this.pendingListingNames) {
            if (!first) {
                builder.append("、");
            }
            builder.append(itemName);
            first = false;
        }
        builder.append("§6正在市场热卖中！");
        this.pendingListingNames.clear();
        this.plugin.getServer().broadcastMessage(builder.toString());
    }

    private static boolean isStructuredOperationEcho(String message) {
        return message.startsWith("§a已购买 ")
            || message.startsWith("§a已出售 ")
            || message.startsWith("§a卖单 #");
    }

    private void loadOffline() {
        if (!this.offlineFile.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(this.offlineFile);
            for (String key : config.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    Object value = config.get(key);
                    PassiveNoticeAggregator aggregator;
                    if (value instanceof ConfigurationSection) {
                        aggregator = PassiveNoticeAggregator.fromYaml((ConfigurationSection)value);
                    } else if (value instanceof List) {
                        aggregator = new PassiveNoticeAggregator();
                        for (Object line : (List<?>)value) {
                            if (line != null) {
                                aggregator.addLegacy(line.toString());
                            }
                        }
                    } else {
                        continue;
                    }
                    if (!aggregator.isEmpty()) {
                        this.passiveOffline.put(uuid, aggregator);
                    }
                }
                catch (IllegalArgumentException ignored) {}
            }
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("Failed to load offline trade notices: "
                + throwable.getMessage());
        }
    }

    private void saveOffline() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, PassiveNoticeAggregator> entry : this.passiveOffline.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                ConfigurationSection section = config.createSection(entry.getKey().toString());
                entry.getValue().toYaml(section);
            }
            config.save(this.offlineFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().warning("Failed to save offline trade notices: " + e.getMessage());
        }
    }
}
