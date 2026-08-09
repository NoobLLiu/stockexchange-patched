package com.github.exchange.notify;

import com.github.exchange.StockExchangePlugin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 交易提醒合并缓冲：手动操作结果 5 秒合并、被动成交提醒 15 秒合并；
 * 离线期间的被动提醒持久化到 offline-notices.yml，玩家上线后合并发送。
 */
public class TradeNoticeBuffer {
    public enum Type {
        MANUAL,
        PASSIVE
    }

    private final StockExchangePlugin plugin;
    private final long manualDelayTicks;
    private final long passiveDelayTicks;
    private final Map<UUID, List<String>> manualLines = new HashMap<UUID, List<String>>();
    private final Map<UUID, List<String>> passiveLines = new HashMap<UUID, List<String>>();
    private final Map<String, BukkitTask> pendingTasks = new HashMap<String, BukkitTask>();
    private final Map<UUID, List<String>> offlineLines = new HashMap<UUID, List<String>>();
    private final File offlineFile;

    public TradeNoticeBuffer(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.manualDelayTicks = Math.max(1L,
            plugin.getConfig().getInt("notify.manual_delay_seconds", 5) * 20L);
        this.passiveDelayTicks = Math.max(1L,
            plugin.getConfig().getInt("notify.passive_delay_seconds", 15) * 20L);
        this.offlineFile = new File(plugin.getDataFolder(), "offline-notices.yml");
        this.loadOffline();
    }

    public void manual(Player player, String message) {
        if (player != null) {
            this.queue(player.getUniqueId(), Type.MANUAL, message);
        }
    }

    public void manual(UUID uuid, String message) {
        this.queue(uuid, Type.MANUAL, message);
    }

    public void passive(Player player, String message) {
        if (player != null) {
            this.queue(player.getUniqueId(), Type.PASSIVE, message);
        }
    }

    public void passive(UUID uuid, String message) {
        this.queue(uuid, Type.PASSIVE, message);
    }

    private synchronized void queue(UUID uuid, Type type, String message) {
        if (uuid == null || message == null || message.isBlank()) {
            return;
        }
        if (type == Type.PASSIVE && Bukkit.getPlayer(uuid) == null) {
            this.offlineLines.computeIfAbsent(uuid, key -> new ArrayList<String>()).add(message);
            this.saveOffline();
            return;
        }
        Map<UUID, List<String>> lines = type == Type.MANUAL ? this.manualLines : this.passiveLines;
        List<String> queued = lines.computeIfAbsent(uuid, key -> new ArrayList<String>());
        boolean first = queued.isEmpty();
        queued.add(message);
        if (first) {
            long delay = type == Type.MANUAL ? this.manualDelayTicks : this.passiveDelayTicks;
            UUID target = uuid;
            Type targetType = type;
            this.pendingTasks.computeIfAbsent(uuid + ":" + type.name(), key ->
                this.plugin.getServer().getScheduler().runTaskLater(this.plugin,
                    () -> this.flush(target, targetType), delay));
        }
    }

    private synchronized void flush(UUID uuid, Type type) {
        this.pendingTasks.remove(uuid + ":" + type.name());
        Map<UUID, List<String>> lines = type == Type.MANUAL ? this.manualLines : this.passiveLines;
        List<String> queued = lines.remove(uuid);
        if (queued == null || queued.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            this.offlineLines.computeIfAbsent(uuid, key -> new ArrayList<String>()).addAll(queued);
            this.saveOffline();
            return;
        }
        this.sendGrouped(player, this.header(type), queued);
    }

    /** 插件关闭前冲刷所有在线缓冲，离线玩家的积攒写入持久化。 */
    public synchronized void flushAll() {
        for (BukkitTask task : this.pendingTasks.values()) {
            task.cancel();
        }
        this.pendingTasks.clear();
        this.flushMap(this.manualLines, Type.MANUAL);
        this.flushMap(this.passiveLines, Type.PASSIVE);
    }

    /** 玩家上线：把离线期间积攒的交易提醒合并发送并清理。 */
    public void flushOffline(Player player) {
        if (player == null) {
            return;
        }
        List<String> queued;
        synchronized (this) {
            queued = this.offlineLines.remove(player.getUniqueId());
            if (queued != null && !queued.isEmpty()) {
                this.saveOffline();
            }
        }
        if (queued != null && !queued.isEmpty() && player.isOnline()) {
            this.sendGrouped(player, "\u00a7e[交易市场·离线交易] \u00a77", queued);
        }
    }

    private void flushMap(Map<UUID, List<String>> lines, Type type) {
        for (Map.Entry<UUID, List<String>> entry : new ArrayList<Map.Entry<UUID, List<String>>>(lines.entrySet())) {
            lines.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                this.sendGrouped(player, this.header(type), entry.getValue());
            } else {
                this.offlineLines.computeIfAbsent(entry.getKey(), key -> new ArrayList<String>()).addAll(entry.getValue());
                this.saveOffline();
            }
        }
    }

    private String header(Type type) {
        return type == Type.MANUAL
            ? "\u00a7e[交易市场·操作] \u00a77"
            : "\u00a7e[交易市场·成交] \u00a77";
    }

    private void sendGrouped(Player player, String header, List<String> queued) {
        StringBuilder sb = new StringBuilder(header);
        sb.append(queued.get(0));
        for (int i = 1; i < queued.size(); ++i) {
            sb.append("\n").append(queued.get(i));
        }
        player.sendMessage(sb.toString());
    }

    private void loadOffline() {
        if (!this.offlineFile.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(this.offlineFile);
            for (String key : config.getKeys(false)) {
                try {
                    List<String> lines = config.getStringList(key);
                    if (!lines.isEmpty()) {
                        this.offlineLines.put(UUID.fromString(key), new ArrayList<String>(lines));
                    }
                }
                catch (IllegalArgumentException ignored) {}
            }
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("Failed to load offline trade notices: " + throwable.getMessage());
        }
    }

    private void saveOffline() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, List<String>> entry : this.offlineLines.entrySet()) {
                config.set(entry.getKey().toString(), entry.getValue());
            }
            config.save(this.offlineFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().warning("Failed to save offline trade notices: " + e.getMessage());
        }
    }
}
