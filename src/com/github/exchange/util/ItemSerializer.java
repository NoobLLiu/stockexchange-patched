/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.enchantments.Enchantment
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 */
package com.github.exchange.util;

import com.github.exchange.StockExchangePlugin;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemSerializer {
    private static StockExchangePlugin plugin;

    private static StockExchangePlugin getPlugin() {
        if (plugin == null) {
            plugin = StockExchangePlugin.getInstance();
        }
        return plugin;
    }

    public static String itemToBase64(ItemStack item) {
        StockExchangePlugin p = ItemSerializer.getPlugin();
        if (p == null || p.getVersionAdapter() == null) {
            return null;
        }
        return p.getVersionAdapter().itemToBase64(item);
    }

    public static ItemStack itemFromBase64(String base64) {
        StockExchangePlugin p = ItemSerializer.getPlugin();
        if (p == null || p.getVersionAdapter() == null) {
            return null;
        }
        return p.getVersionAdapter().itemFromBase64(base64);
    }

    public static String calculateNbtHash(ItemStack item) {
        String input;
        StringBuilder sb = new StringBuilder();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            sb.append("name:").append(meta.getDisplayName()).append("|");
        }
        if (meta != null && meta.hasLore()) {
            List<String> lore = meta.getLore();
            String loreStr = lore.stream().collect(Collectors.joining("\n"));
            sb.append("lore:").append(loreStr).append("|");
        }
        if (meta != null && meta.hasEnchants()) {
            Map<Enchantment, Integer> enchants = meta.getEnchants();
            String enchantStr = enchants.entrySet().stream().sorted((a, b) -> a.getKey().getKey().toString().compareTo(b.getKey().getKey().toString())).map(e -> e.getKey().getKey().toString() + ":" + e.getValue()).collect(Collectors.joining(","));
            sb.append("enchants:").append(enchantStr).append("|");
        }
        if ((input = sb.toString()).isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b2 : hash) {
                String hex = Integer.toHexString(0xFF & b2);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        }
        catch (NoSuchAlgorithmException e2) {
            e2.printStackTrace();
            return String.valueOf(input.hashCode());
        }
    }

    public static String getItemDisplayName(ItemStack item) {
        StockExchangePlugin p = ItemSerializer.getPlugin();
        if (p == null || p.getVersionAdapter() == null) {
            return item.getType().name();
        }
        return p.getVersionAdapter().getItemName(item);
    }
}
