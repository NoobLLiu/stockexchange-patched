/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.util;

import com.github.exchange.gui.MarketListingSearch;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class ItemDatabase {
    private static final String DATA_FILE = "data/item_database.json";
    private final List<ItemEntry> items;
    private final Logger logger;

    public ItemDatabase(Logger logger) {
        this.logger = logger;
        List<ItemEntry> loaded = this.loadItems();
        this.items = loaded != null ? loaded : new ArrayList<ItemEntry>();
    }

    private List<ItemEntry> loadItems() {
        try {
            InputStream is = this.getClass().getClassLoader().getResourceAsStream(DATA_FILE);
            if (is == null) {
                this.logger.warning("Item database file not found: " + DATA_FILE);
                return null;
            }
            InputStreamReader reader = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<ItemEntry>>(){}.getType();
            List<ItemEntry> result = new Gson().fromJson(reader, listType);
            reader.close();
            this.logger.info("Loaded " + (result != null ? result.size() : 0) + " items from item database");
            return result;
        }
        catch (Exception e) {
            this.logger.warning("Failed to load item database: " + e.getMessage());
            return null;
        }
    }

    public int size() {
        return this.items.size();
    }

    public ItemEntry findById(String itemId) {
        String normalizedId = itemId.trim().toLowerCase();
        for (ItemEntry entry : this.items) {
            if (entry.getId().equals(normalizedId)) {
                return entry;
            }
        }
        return null;
    }

    public ItemEntry findByName(String chineseName) {
        String normalized = chineseName.trim();
        for (ItemEntry entry : this.items) {
            if (entry.getName().equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    public ItemEntry search(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        for (ItemEntry entry : this.items) {
            if (MarketListingSearch.matches(trimmed, 0, entry.getName(), entry.getName(),
                    entry.getId(), entry.getId(), entry.getId().toUpperCase())) {
                return entry;
            }
        }
        return null;
    }

    public List<ItemEntry> searchAll(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = query.trim();
        List<ItemEntry> results = new ArrayList<ItemEntry>();
        for (ItemEntry entry : this.items) {
            if (MarketListingSearch.matches(trimmed, 0, entry.getName(), entry.getName(),
                    entry.getId(), entry.getId(), entry.getId().toUpperCase())) {
                results.add(entry);
            } else if (entry.getId().equals(trimmed.toLowerCase()) || entry.getName().equals(trimmed)) {
                results.add(0, entry);
            }
        }
        return results;
    }

    public Material resolveMaterial(ItemEntry entry) {
        if (entry == null || entry.getId() == null) {
            return null;
        }
        String materialName = entry.getId().toUpperCase();
        Material material = Material.getMaterial(materialName);
        if (material != null && material.isItem()) {
            return material;
        }
        material = Material.matchMaterial(entry.getId());
        if (material != null && material.isItem()) {
            return material;
        }
        return null;
    }

    public ItemStack createItemStack(ItemEntry entry) {
        if (entry == null) {
            return null;
        }
        Material material = this.resolveMaterial(entry);
        if (material == null) {
            return null;
        }
        return new ItemStack(material, 1);
    }

    public static class ItemEntry {
        private String name;
        private String id;

        public ItemEntry() {
        }

        public ItemEntry(String name, String id) {
            this.name = name;
            this.id = id;
        }

        public String getName() {
            return this.name;
        }

        public String getId() {
            return this.id;
        }
    }
}
