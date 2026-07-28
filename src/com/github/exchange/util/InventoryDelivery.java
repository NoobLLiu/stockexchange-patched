package com.github.exchange.util;

import com.github.exchange.manager.DeliveryPlan;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class InventoryDelivery {
    private InventoryDelivery() {
    }

    public static int addUpTo(Player player, ItemStack baseItem, int quantity) {
        if (player == null || baseItem == null || quantity <= 0) {
            return 0;
        }

        int added = 0;
        int maxStack = Math.max(1, baseItem.getMaxStackSize());
        for (int chunkAmount : DeliveryPlan.chunks(quantity, maxStack)) {
            ItemStack chunk = baseItem.clone();
            chunk.setAmount(chunkAmount);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(chunk);
            int leftoverAmount = leftovers.values().stream()
                .filter(item -> item != null)
                .mapToInt(ItemStack::getAmount)
                .sum();
            added += chunkAmount - leftoverAmount;
            if (leftoverAmount > 0) {
                break;
            }
        }
        return added;
    }
}
