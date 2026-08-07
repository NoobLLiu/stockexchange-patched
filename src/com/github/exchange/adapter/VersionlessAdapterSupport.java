package com.github.exchange.adapter;

import com.github.exchange.StockExchangePlugin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
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
            logFailure("serialize an item stack", exception);
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
            logFailure("deserialize an item stack", exception);
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
            logFailure("retitle an exchange inventory", exception);
        }
    }

    private static void logFailure(String operation, Exception exception) {
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        Logger logger = plugin == null ? Bukkit.getLogger() : plugin.getLogger();
        logger.log(Level.WARNING, "Unable to " + operation, exception);
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
