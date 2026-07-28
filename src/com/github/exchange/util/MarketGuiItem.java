package com.github.exchange.util;

import com.github.exchange.StockExchangePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class MarketGuiItem {
    private static final String KEY_NAME = "gui_display";

    private MarketGuiItem() {
    }

    public static void mark(ItemStack item) {
        if (item == null) {
            return;
        }
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte)1));
    }

    public static boolean isMarked(ItemStack item) {
        return item != null
            && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
    }

    private static NamespacedKey key() {
        return new NamespacedKey(StockExchangePlugin.getInstance(), KEY_NAME);
    }
}
