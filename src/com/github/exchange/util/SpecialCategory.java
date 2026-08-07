package com.github.exchange.util;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * 交易市场的特殊聚合类别：同类物品共用一个代表品种。
 * Slimefun 指南（ENCHANTED_BOOK 材质但带自定义模型/数据）不算附魔书。
 */
public enum SpecialCategory {
    ENCHANTED_BOOK("附魔书", Material.ENCHANTED_BOOK),
    ARMOR_AND_TOOLS("盔甲与工具", Material.DIAMOND_CHESTPLATE),
    POTION("药水", Material.POTION),
    MUSIC_DISC("唱片", Material.MUSIC_DISC_13);

    private final String displayName;
    private final Material representativeMaterial;

    SpecialCategory(String displayName, Material representativeMaterial) {
        this.displayName = displayName;
        this.representativeMaterial = representativeMaterial;
    }

    public String displayName() {
        return this.displayName;
    }

    public Material representativeMaterial() {
        return this.representativeMaterial;
    }

    public static SpecialCategory of(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        Material type = item.getType();
        if (isSlimefunLike(item)) {
            return null;
        }
        if (type == Material.ENCHANTED_BOOK) {
            return ENCHANTED_BOOK;
        }
        if (isArmorOrTool(type)) {
            return ARMOR_AND_TOOLS;
        }
        if (type == Material.POTION
            || type == Material.SPLASH_POTION
            || type == Material.LINGERING_POTION) {
            return POTION;
        }
        if (type.name().startsWith("MUSIC_DISC_")) {
            return MUSIC_DISC;
        }
        return null;
    }

    public ItemStack createRepresentative() {
        ItemStack stack = new ItemStack(this.representativeMaterial, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(this.displayName));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static boolean isArmorOrTool(Material type) {
        String name = type.name();
        if (name.endsWith("_HELMET")
            || name.endsWith("_CHESTPLATE")
            || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS")
            || name.endsWith("_SWORD")
            || name.endsWith("_PICKAXE")
            || name.endsWith("_AXE")
            || name.endsWith("_SHOVEL")
            || name.endsWith("_HOE")
            || name.endsWith("_HORSE_ARMOR")) {
            return true;
        }
        switch (type) {
            case TURTLE_HELMET:
            case WOLF_ARMOR:
            case ELYTRA:
            case BOW:
            case CROSSBOW:
            case TRIDENT:
            case FISHING_ROD:
            case SHEARS:
            case FLINT_AND_STEEL:
            case SHIELD:
            case MACE:
            case BRUSH:
            case SPYGLASS:
            case CARROT_ON_A_STICK:
            case WARPED_FUNGUS_ON_A_STICK:
                return true;
            default:
                return false;
        }
    }

    private static boolean isSlimefunLike(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.hasCustomModelData()) {
            return true;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container == null) {
            return false;
        }
        for (NamespacedKey key : container.getKeys()) {
            if ("slimefun".equalsIgnoreCase(key.getNamespace())) {
                return true;
            }
        }
        return false;
    }
}
