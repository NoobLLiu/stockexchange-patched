package com.github.exchange.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemDisplayNames {
    private static final Properties VANILLA_ZH_CN = loadTranslations();

    private ItemDisplayNames() {
    }

    public static String resolve(ItemStack item) {
        if (item == null) {
            return "";
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String customName = meta.getDisplayName();
            if (customName != null && !customName.isBlank()) {
                return customName;
            }
        }

        String translated = resolveTranslationKey(item.translationKey());
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        return humanize(item.getType().name());
    }

    static String resolveTranslationKey(String translationKey) {
        return translationKey == null ? null : VANILLA_ZH_CN.getProperty(translationKey);
    }

    public static boolean isRawMaterialId(String name, ItemStack item) {
        if (name == null || name.isBlank() || item == null) {
            return true;
        }
        String plain = ChatColor.stripColor(name);
        return plain == null
            || plain.equalsIgnoreCase(item.getType().name())
            || plain.equalsIgnoreCase(item.getType().getKey().getKey());
    }

    private static Properties loadTranslations() {
        Properties properties = new Properties();
        try (InputStream stream = ItemDisplayNames.class.getClassLoader().getResourceAsStream("vanilla-zh-cn.properties")) {
            if (stream != null) {
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static String humanize(String materialName) {
        String[] parts = materialName.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
}
