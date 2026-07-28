package com.github.exchange.adapter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;

final class VersionlessAdapterSupport {
    private VersionlessAdapterSupport() {
    }

    static String itemToBase64(ItemStack item) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("item", item);
            return Base64.getEncoder().encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    static ItemStack itemFromBase64(String data) {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(decoded, StandardCharsets.UTF_8));
            return yaml.getItemStack("item");
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    static void setInventoryTitle(Player player, Inventory inventory, String title) {
        try {
            InventoryView view = player.openInventory(inventory);
            if (view != null && title != null) {
                try {
                    view.setTitle(title);
                } catch (Throwable ignored) {
                    // Some server implementations do not support retitling after opening.
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    static String getItemName(ItemStack item) {
        if (item == null) {
            return "";
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return item.getType().name();
    }
}
