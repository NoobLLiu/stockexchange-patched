package com.github.exchange.listener;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.storage.StorageManager;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.InventoryDelivery;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.ItemSerializer;
import java.math.BigDecimal;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

public class SettlementDeliveryListener implements Listener {
    private final StockExchangePlugin plugin;

    public SettlementDeliveryListener(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            this.deliverPendingAssets(player);
            this.plugin.getTradeNoticeBuffer().flushOffline(player);
        }, 20L);
    }

    private void deliverPendingAssets(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        StorageManager storage = this.plugin.getStorageManager();
        String playerUuid = player.getUniqueId().toString();
        BigDecimal pendingMoney = storage.getMoneyWarehouseBalance(playerUuid);
        if (pendingMoney.compareTo(BigDecimal.ZERO) > 0
            && storage.takeFromMoneyWarehouse(playerUuid, pendingMoney)) {
            if (EconomyUtil.deposit(player.getUniqueId(), pendingMoney)) {
                player.sendMessage("\u00a7a\u4f60\u79bb\u7ebf\u671f\u95f4\u4ea7\u751f\u7684 " + pendingMoney.toPlainString() + " " + this.plugin.getCurrencyName() + " \u5df2\u81ea\u52a8\u5230\u8d26\u3002");
            } else if (!storage.addToMoneyWarehouse(playerUuid, pendingMoney)) {
                this.plugin.getLogger().severe("[AssetAudit] MONEY_JOIN_ROLLBACK_FAILED player=" + playerUuid
                    + " amount=" + pendingMoney);
                player.sendMessage("\u00a7c\u79bb\u7ebf\u661f\u5149\u70b9\u53d1\u653e\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
            } else {
                player.sendMessage("\u00a7c\u5f53\u524d\u65e0\u6cd5\u53d1\u653e\u79bb\u7ebf\u661f\u5149\u70b9\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002");
            }
        }
        Map<String, Integer> pendingItems = storage.getPlayerItemWarehouse(playerUuid);
        int delivered = 0;
        int retained = 0;
        for (Map.Entry<String, Integer> entry : pendingItems.entrySet()) {
            ItemStack baseItem = ItemSerializer.itemFromBase64(entry.getKey());
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (baseItem == null || quantity <= 0) {
                continue;
            }
            if (!storage.takeFromPlayerItemWarehouse(playerUuid, entry.getKey(), quantity)) {
                continue;
            }
            String displayName = ItemDisplayNames.resolve(baseItem);
            try {
                int added = InventoryDelivery.addUpTo(player, baseItem, quantity);
                int remaining = quantity - added;
                delivered += added;
                if (remaining > 0 && storage.addToPlayerItemWarehouse(playerUuid, entry.getKey(), remaining)) {
                    retained += remaining;
                } else if (remaining > 0) {
                    this.plugin.getLogger().severe("[AssetAudit] ITEM_JOIN_RESTORE_FAILED player=" + playerUuid
                        + " item=" + entry.getKey() + " quantity=" + remaining);
                    player.sendMessage("\u00a7c\u7269\u54c1\u53d1\u653e\u540e\u56de\u5b58\u5931\u8d25\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
                }
            }
            catch (Throwable throwable) {
                if (!storage.addToPlayerItemWarehouse(playerUuid, entry.getKey(), quantity)) {
                    this.plugin.getLogger().severe("[AssetAudit] ITEM_JOIN_ROLLBACK_FAILED player=" + playerUuid
                        + " item=" + entry.getKey() + " quantity=" + quantity);
                    player.sendMessage("\u00a7c\u79bb\u7ebf\u7269\u54c1\u53d1\u653e\u53d1\u751f\u5f02\u5e38\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
                }
            }
        }
        if (delivered > 0 || retained > 0) {
            StringBuilder msg = new StringBuilder("\u00a7a\u4f60\u79bb\u7ebf\u671f\u95f4\u8d2d\u4e70\u6216\u6210\u4ea4\u7684\u7269\u54c1\u5df2\u81ea\u52a8\u53d1\u653e");
            if (delivered > 0) {
                msg.append("\uff0c\u80cc\u5305\u53d6\u5f97 x").append(delivered);
            }
            if (retained > 0) {
                msg.append("\uff0c\u80cc\u5305\u5df2\u6ee1\uff0c\u4ed3\u5e93\u4fdd\u7559 x").append(retained);
            }
            msg.append("\u3002");
            player.sendMessage(msg.toString());
        }
    }
}
