package com.github.exchange.util;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.gui.BuySearchCatalog;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 求购搜索用的黏液科技物品源（Slimefun 为软依赖）。
 *
 * <p>Slimefun 存在时返回其全部已启用物品；显示名优先取 SlimefunTranslation 的 zh-CN 翻译
 * （含附属汉化），取不到时回退物品自带显示名，再回退物品 ID。</p>
 */
public final class SlimefunSearch {

    private static volatile boolean translationsLoaded;
    private static final Map<String, String> ZH_NAMES = new HashMap<String, String>();
    private static final Pattern SERIALIZED_SLIMEFUN_ID = Pattern.compile(
        "\"slimefun:slimefun_item\"\\s*:\\s*\"([^\"]+)\""
    );

    private SlimefunSearch() {
    }

    public static boolean available() {
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("Slimefun");
        return plugin != null && plugin.isEnabled();
    }

    public static List<BuySearchCatalog.Source> collect(StockExchangePlugin plugin) {
        if (!available()) {
            return Collections.emptyList();
        }
        ensureTranslationsLoaded();
        List<BuySearchCatalog.Source> sources = new ArrayList<BuySearchCatalog.Source>();
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getEnabledSlimefunItems()) {
                if (item == null) {
                    continue;
                }
                String id = item.getId();
                if (id == null || id.isEmpty()) {
                    continue;
                }
                ItemStack stack = item.getItem();
                if (stack == null || stack.getType() == Material.AIR) {
                    continue;
                }
                String displayName = resolveName(id, stack);
                if (displayName == null || displayName.isBlank()) {
                    continue;
                }
                sources.add(BuySearchCatalog.Source.slimefun(id, displayName, stack.getType().name()));
            }
        }
        catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("[SlimefunSearch] Failed to collect Slimefun items: " + e.getMessage());
        }
        return sources;
    }

    /** 点击结果时按 ID 还原黏液科技物品（返回克隆，避免共享实例被修改）。 */
    public static ItemStack itemById(String id) {
        if (id == null || id.isEmpty() || !available()) {
            return null;
        }
        try {
            SlimefunItem item = SlimefunItem.getById(id);
            if (item == null) {
                return null;
            }
            ItemStack stack = item.getItem();
            return ItemSerializer.copyAsPlainItemStack(stack);
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Recovers a legacy catalog stack that was serialized as SlimefunItemStack
     * rather than Bukkit's base ItemStack class.
     */
    public static ItemStack itemFromSerializedReference(String base64) {
        String itemId = serializedSlimefunItemId(base64);
        return itemId == null ? null : itemById(itemId);
    }

    static String serializedSlimefunItemId(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            String serialized = new String(
                Base64.getDecoder().decode(base64),
                java.nio.charset.StandardCharsets.UTF_8
            );
            Matcher matcher = SERIALIZED_SLIMEFUN_ID.matcher(serialized);
            if (!matcher.find()) {
                return null;
            }
            String itemId = matcher.group(1);
            return itemId == null || itemId.isBlank() ? null : itemId;
        }
        catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String resolveName(String id, ItemStack stack) {
        String translated = ZH_NAMES.get(id.toUpperCase(Locale.ROOT));
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String name = meta.getDisplayName();
            if (name != null && !name.isBlank()) {
                String plain = ChatColor.stripColor(name);
                if (plain != null && !plain.isBlank()) {
                    return plain;
                }
            }
        }
        return id;
    }

    private static void ensureTranslationsLoaded() {
        if (translationsLoaded) {
            return;
        }
        synchronized (ZH_NAMES) {
            if (translationsLoaded) {
                return;
            }
            loadTranslations();
            translationsLoaded = true;
        }
    }

    private static void loadTranslations() {
        try {
            org.bukkit.plugin.Plugin translator = Bukkit.getPluginManager().getPlugin("SlimefunTranslation");
            if (translator == null) {
                return;
            }
            File langDir = new File(translator.getDataFolder(), "translations" + File.separator + "zh-CN");
            if (!langDir.isDirectory()) {
                return;
            }
            List<File> files = new ArrayList<File>();
            collectYmlFiles(langDir, files);
            for (File file : files) {
                try {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    ConfigurationSection names = config.getConfigurationSection("translations");
                    if (names == null) {
                        continue;
                    }
                    for (String id : names.getKeys(false)) {
                        String raw = names.getString(id + ".name");
                        if (raw == null) {
                            continue;
                        }
                        String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', raw));
                        if (plain != null && !plain.isBlank()) {
                            ZH_NAMES.putIfAbsent(id.toUpperCase(Locale.ROOT), plain);
                        }
                    }
                }
                catch (RuntimeException ignored) {
                }
            }
        }
        catch (RuntimeException ignored) {
        }
    }

    private static void collectYmlFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectYmlFiles(child, out);
            }
            else if (child.getName().endsWith(".yml")) {
                out.add(child);
            }
        }
    }
}
