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
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.deliverPendingAssets(player), 20L);
    }

    private void deliverPendingAssets(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        StorageManager storage = this.plugin.getStorageManager();
        String playerUuid = player.getUniqueId().toString();
        BigDecimal pendingMoney = storage.getMoneyWarehouseBalance(playerUuid);
        if (pendingMoney.compareTo(BigDecimal.ZERO) > 0 && EconomyUtil.deposit(player.getUniqueId(), pendingMoney)) {
            storage.takeFromMoneyWarehouse(playerUuid, pendingMoney);
            player.sendMessage("\u00a7a\u4f60\u79bb\u7ebf\u671f\u95f4\u4ea7\u751f\u7684 " + pendingMoney.toPlainString() + " " + this.plugin.getCurrencyName() + " \u5df2\u81ea\u52a8\u5230\u8d26\u3002");
        }
        Map<String, Integer> pendingItems = storage.getPlayerItemWarehouse(playerUuid);
        int delivered = 0;
        int mailed = 0;
        int dropped = 0;
        for (Map.Entry<String, Integer> entry : pendingItems.entrySet()) {
            ItemStack baseItem = ItemSerializer.itemFromBase64(entry.getKey());
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (baseItem == null || quantity <= 0) {
                continue;
            }
            String displayName = ItemDisplayNames.resolve(baseItem);
            int added = InventoryDelivery.addUpTo(player, baseItem, quantity);
            int remaining = quantity - added;
            delivered += added;
            if (remaining > 0) {
                ItemStack leftoverStack = baseItem.clone();
                leftoverStack.setAmount(remaining);
                if (this.plugin.sendItemsAsMail(player, displayName, leftoverStack)) {
                    mailed += remaining;
                } else {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftoverStack);
                    dropped += remaining;
                }
            }
            storage.takeFromPlayerItemWarehouse(playerUuid, entry.getKey(), quantity);
        }
        if (delivered > 0 || mailed > 0 || dropped > 0) {
            StringBuilder msg = new StringBuilder("\u00a7a\u4f60\u79bb\u7ebf\u671f\u95f4\u8d2d\u4e70\u6216\u6210\u4ea4\u7684\u7269\u54c1\u5df2\u81ea\u52a8\u53d1\u653e");
            if (delivered > 0) {
                msg.append("\uff0c\u80cc\u5305\u53d6\u5f97 x").append(delivered);
            }
            if (mailed > 0) {
                msg.append("\uff0c\u90ae\u4ef6\u53d1\u653e x").append(mailed);
            }
            if (dropped > 0) {
                msg.append("\uff0c\u5730\u9762\u6389\u843d x").append(dropped);
            }
            msg.append("\u3002");
            player.sendMessage(msg.toString());
        }
    }
}
