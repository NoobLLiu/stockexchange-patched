/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 */
package com.github.exchange.manager;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.SpecialCategory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class ItemManager {
    private final StockExchangePlugin plugin;
    private final NamespacedKey specialCategoryKey;

    public ItemManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.specialCategoryKey = new NamespacedKey(plugin, "special_category");
    }

    public void normalizeCatalogDisplayNames() {
        for (ExchangeItem exchangeItem : this.plugin.getStorageManager().getAllExchangeItems()) {
            ItemStack item = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
            if (item == null || !ItemDisplayNames.isRawMaterialId(exchangeItem.getDisplayName(), item)) {
                continue;
            }
            String translatedName = ItemDisplayNames.resolve(item);
            exchangeItem.setDisplayName(translatedName);
            exchangeItem.setItemName(translatedName);
            this.plugin.getStorageManager().updateExchangeItem(exchangeItem);
        }
    }

    public ExchangeItem registerItem(ItemStack item) {
        return this.registerItem(item, null);
    }

    public ExchangeItem registerItem(ItemStack item, Player creator) {
        return this.registerItem(item, creator, true);
    }

    public ExchangeItem registerItem(
        ItemStack item,
        Player creator,
        boolean activateSellCatalog
    ) {
        return this.registerItem(
            item,
            creator == null ? null : creator.getUniqueId().toString(),
            creator == null ? null : creator.getName(),
            activateSellCatalog
        );
    }

    public ExchangeItem registerItem(ItemStack item, String creatorUuid, String creatorName) {
        return this.registerItem(item, creatorUuid, creatorName, true);
    }

    private ExchangeItem registerItem(
        ItemStack item,
        String creatorUuid,
        String creatorName,
        boolean activateSellCatalog
    ) {
        ExchangeItem special = this.resolveSpecialItem(item);
        if (special != null) {
            return special;
        }
        String displayName;
        String material = item.getType().name();
        String nbtHash = ItemSerializer.calculateNbtHash(item);
        String base64 = ItemSerializer.itemToBase64(item);
        String itemName = displayName = ItemDisplayNames.resolve(item);
        String itemLore = "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                itemName = meta.getDisplayName();
                displayName = itemName;
            }
            if (meta.hasLore() && meta.getLore() != null) {
                itemLore = String.join((CharSequence)"\n", new ArrayList(meta.getLore()));
            }
        }
        if (base64 == null) {
            this.plugin.getLogger().severe("Failed to serialize item to base64!");
            return null;
        }
        ExchangeItem existing = this.plugin.getStorageManager().getExchangeItemByHash(material, nbtHash);
        if (existing != null) {
            if (activateSellCatalog) {
                this.markSellCatalogActivity(existing);
            }
            return existing;
        }
        ExchangeItem exchangeItem = new ExchangeItem();
        exchangeItem.setMaterial(material);
        exchangeItem.setNbtHash(nbtHash);
        exchangeItem.setItemBase64(base64);
        exchangeItem.setDisplayName(displayName);
        exchangeItem.setItemName(itemName);
        exchangeItem.setItemLore(itemLore);
        if (creatorUuid != null && !creatorUuid.isEmpty()) {
            exchangeItem.setCreatedByUuid(creatorUuid);
            exchangeItem.setCreatedByName(creatorName);
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        exchangeItem.setCreatedAt(now);
        exchangeItem.setLastStockedAt(now);
        if (activateSellCatalog) {
            exchangeItem.setLastSellCatalogActivityAt(now);
        }
        exchangeItem.setLastEmptyAt(null);
        int id = this.plugin.getStorageManager().insertExchangeItem(exchangeItem);
        if (id > 0) {
            exchangeItem.setId(id);
            ItemStatus status = new ItemStatus();
            status.setItemId(id);
            status.setSuspended(false);
            status.setLastClose(BigDecimal.ZERO);
            status.setLastOpen(BigDecimal.ZERO);
            status.setHighToday(BigDecimal.ZERO);
            status.setLowToday(BigDecimal.ZERO);
            status.setVolumeToday(0);
            status.setLowestSellCurrent(BigDecimal.ZERO);
            status.setLowestSellReference(BigDecimal.ZERO);
            status.setLowestSellReferenceAt(0L);
            status.setLowestSellReference7d(BigDecimal.ZERO);
            status.setLowestSellReferenceAt7d(0L);
            status.setLowestSellReference30d(BigDecimal.ZERO);
            status.setLowestSellReferenceAt30d(0L);
            this.plugin.getStorageManager().upsertItemStatus(status);
            return exchangeItem;
        }
        return this.plugin.getStorageManager().getExchangeItemByHash(material, nbtHash);
    }

    public RegisterResult registerOrRestock(Player player, ItemStack item, int quantity) {
        if (player == null || item == null || item.getType() == Material.AIR || quantity <= 0) {
            return new RegisterResult(false, false, null, "\u00a7c\u65e0\u6548\u7684\u7269\u54c1\u6216\u6570\u91cf\u3002");
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return new RegisterResult(false, false, null, this.plugin.growthAccessMessage(player));
        }

        SpecialCategory specialCategory = SpecialCategory.of(item);
        if (specialCategory != null) {
            ExchangeItem special = this.getOrCreateCategoryItem(specialCategory);
            if (special == null) {
                return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u6ce8\u518c\u5931\u8d25\u3002");
            }
            String actualBase64 = ItemSerializer.itemToBase64(item);
            if (actualBase64 == null
                || !this.plugin.getStorageManager().addToWarehouse(actualBase64, quantity)) {
                return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u8865\u8d27\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
            }
            return new RegisterResult(true, false, special,
                "\u00a7a\u5df2\u4e3a\u300c" + specialCategory.displayName() + "\u300d\u7c7b\u522b\u8865\u8d27 " + quantity + " \u4e2a\u3002");
        }

        String material = item.getType().name();
        String nbtHash = ItemSerializer.calculateNbtHash(item);
        ExchangeItem existing = this.plugin.getStorageManager().getExchangeItemByHash(material, nbtHash);
        if (existing == null) {
            if (!player.hasPermission("exchange.admin")) {
                int used = this.plugin.getStorageManager().getDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now());
                if (used >= this.plugin.getDailyRegisterLimit()) {
                    return new RegisterResult(false, false, null, "\u00a7c\u4f60\u4eca\u5929\u6700\u591a\u53ea\u80fd\u65b0\u589e " + this.plugin.getDailyRegisterLimit() + " \u79cd\u5546\u54c1\u3002");
                }
            }
            existing = this.registerItem(item, player);
            if (existing == null) {
                return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u6ce8\u518c\u5931\u8d25\u3002");
            }
            if (!this.plugin.getStorageManager().addToWarehouse(existing.getItemBase64(), quantity)) {
                this.plugin.getStorageManager().deleteExchangeItem(existing.getId());
                return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u8865\u8d27\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
            }
            if (!player.hasPermission("exchange.admin")) {
                int used = this.plugin.getStorageManager().getDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now());
                this.plugin.getStorageManager().setDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now(), used + 1);
            }
            return new RegisterResult(true, true, existing, "\u00a7a\u5546\u54c1\u5df2\u4e0a\u5e02\u5e76\u8865\u8d27 " + quantity + " \u4e2a\u3002");
        }

        if (!this.plugin.getStorageManager().addToWarehouse(existing.getItemBase64(), quantity)) {
            return new RegisterResult(false, false, existing, "\u00a7c\u5546\u54c1\u8865\u8d27\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        existing.setLastStockedAt(now);
        existing.setLastSellCatalogActivityAt(now);
        existing.setLastEmptyAt(null);
        this.plugin.getStorageManager().updateExchangeItem(existing);
        return new RegisterResult(true, false, existing, "\u00a7a\u5df2\u4e3a\u5546\u54c1 #" + existing.getId() + " \u8865\u8d27 " + quantity + " \u4e2a\u3002");
    }

    public RegisterResult registerCatalogItem(Player player, ItemStack item) {
        return this.registerCatalogItem(player, item, true);
    }

    public RegisterResult registerCatalogItem(
        Player player,
        ItemStack item,
        boolean activateSellCatalog
    ) {
        if (player == null || item == null || item.getType() == Material.AIR) {
            return new RegisterResult(false, false, null, "\u00a7c\u65e0\u6548\u7684\u7269\u54c1\u3002");
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return new RegisterResult(false, false, null, this.plugin.growthAccessMessage(player));
        }
        ExchangeItem special = this.resolveSpecialItem(item);
        if (special != null) {
            return new RegisterResult(true, false, special,
                "\u00a7a\u8be5\u7269\u54c1\u5f52\u5165\u300c" + SpecialCategory.of(item).displayName()
                    + "\u300d\u7c7b\u522b\uff0c\u53ef\u76f4\u63a5\u5728\u7c7b\u522b\u8be6\u60c5\u9875\u4e0a\u67b6\u3002");
        }
        String material = item.getType().name();
        String nbtHash = ItemSerializer.calculateNbtHash(item);
        ExchangeItem existing = this.plugin.getStorageManager().getExchangeItemByHash(material, nbtHash);
        if (existing != null) {
            if (activateSellCatalog) {
                this.markSellCatalogActivity(existing);
            }
            return new RegisterResult(true, false, existing, "\u00a7a\u8be5\u5546\u54c1\u5df2\u5728\u5e02\u573a\u76ee\u5f55\u4e2d\u3002");
        }
        if (!player.hasPermission("exchange.admin")) {
            int used = this.plugin.getStorageManager().getDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now());
            if (used >= this.plugin.getDailyRegisterLimit()) {
                return new RegisterResult(false, false, null, "\u00a7c\u4f60\u4eca\u5929\u6700\u591a\u53ea\u80fd\u65b0\u589e " + this.plugin.getDailyRegisterLimit() + " \u79cd\u5546\u54c1\u3002");
            }
        }
        ExchangeItem created = this.registerItem(item, player, activateSellCatalog);
        if (created == null) {
            return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u76ee\u5f55\u6dfb\u52a0\u5931\u8d25\u3002");
        }
        if (!player.hasPermission("exchange.admin")) {
            int used = this.plugin.getStorageManager().getDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now());
            this.plugin.getStorageManager().setDailyRegisterCount(player.getUniqueId().toString(), LocalDate.now(), used + 1);
        }
        return new RegisterResult(true, true, created, "\u00a7a\u5df2\u5c06\u5546\u54c1\u52a0\u5165\u5e02\u573a\u76ee\u5f55\uff0c\u4f60\u73b0\u5728\u53ef\u4ee5\u5728\u5546\u54c1\u8be6\u60c5\u9875\u4e0a\u67b6\u8be5\u7269\u54c1\u3002");
    }

    /** 网页导出接口：以玩家 UUID 注册目录商品（不要求在线），admin=true 时跳过每日新增上限。 */
    public RegisterResult registerCatalogItem(String playerUuid, String playerName, ItemStack item, boolean admin) {
        return this.registerCatalogItem(playerUuid, playerName, item, admin, true);
    }

    public RegisterResult registerCatalogItem(
        String playerUuid,
        String playerName,
        ItemStack item,
        boolean admin,
        boolean activateSellCatalog
    ) {
        if (playerUuid == null || item == null || item.getType() == Material.AIR) {
            return new RegisterResult(false, false, null, "\u00a7c\u65e0\u6548\u7684\u7269\u54c1\u3002");
        }
        if (this.plugin.isGrowthAccessRestricted(playerUuid)) {
            return new RegisterResult(false, false, null, this.plugin.growthAccessMessage(playerUuid));
        }
        ExchangeItem special = this.resolveSpecialItem(item);
        if (special != null) {
            return new RegisterResult(true, false, special,
                "\u00a7a\u8be5\u7269\u54c1\u5f52\u5165\u300c" + SpecialCategory.of(item).displayName()
                    + "\u300d\u7c7b\u522b\uff0c\u53ef\u76f4\u63a5\u5728\u7c7b\u522b\u8be6\u60c5\u9875\u4e0a\u67b6\u3002");
        }
        String material = item.getType().name();
        String nbtHash = ItemSerializer.calculateNbtHash(item);
        ExchangeItem existing = this.plugin.getStorageManager().getExchangeItemByHash(material, nbtHash);
        if (existing != null) {
            if (activateSellCatalog) {
                this.markSellCatalogActivity(existing);
            }
            return new RegisterResult(true, false, existing, "\u00a7a\u8be5\u5546\u54c1\u5df2\u5728\u5e02\u573a\u76ee\u5f55\u4e2d\u3002");
        }
        if (!admin) {
            int used = this.plugin.getStorageManager().getDailyRegisterCount(playerUuid, LocalDate.now());
            if (used >= this.plugin.getDailyRegisterLimit()) {
                return new RegisterResult(false, false, null, "\u00a7c\u4f60\u4eca\u5929\u6700\u591a\u53ea\u80fd\u65b0\u589e "
                    + this.plugin.getDailyRegisterLimit() + " \u79cd\u5546\u54c1\u3002");
            }
        }
        ExchangeItem created = this.registerItem(
            item,
            playerUuid,
            playerName,
            activateSellCatalog
        );
        if (created == null) {
            return new RegisterResult(false, false, null, "\u00a7c\u5546\u54c1\u76ee\u5f55\u6dfb\u52a0\u5931\u8d25\u3002");
        }
        if (!admin) {
            int used = this.plugin.getStorageManager().getDailyRegisterCount(playerUuid, LocalDate.now());
            this.plugin.getStorageManager().setDailyRegisterCount(playerUuid, LocalDate.now(), used + 1);
        }
        return new RegisterResult(true, true, created, "\u00a7a\u5df2\u5c06\u5546\u54c1\u52a0\u5165\u5e02\u573a\u76ee\u5f55\uff0c\u4f60\u73b0\u5728\u53ef\u4ee5\u5728\u5546\u54c1\u8be6\u60c5\u9875\u4e0a\u67b6\u8be5\u7269\u54c1\u3002");
    }

    private void markSellCatalogActivity(ExchangeItem item) {
        if (item == null || this.getSpecialCategory(item) != null) {
            return;
        }
        item.setLastSellCatalogActivityAt(new Timestamp(System.currentTimeMillis()));
        this.plugin.getStorageManager().updateExchangeItem(item);
    }

    public void cleanupExpiredEmptyItems() {
        long now = System.currentTimeMillis();
        for (ExchangeItem item : this.plugin.getStorageManager().getAllExchangeItems()) {
            int stock = this.plugin.getOrderManager().getCurrentSellStock(item.getId());
            if (stock > 0) {
                if (item.getLastEmptyAt() != null) {
                    item.setLastEmptyAt(null);
                    this.plugin.getStorageManager().updateExchangeItem(item);
                }
                continue;
            }
            Timestamp lastEmptyAt = item.getLastEmptyAt();
            if (lastEmptyAt == null) {
                item.setLastEmptyAt(new Timestamp(now));
                this.plugin.getStorageManager().updateExchangeItem(item);
            }
        }
    }

    public ExchangeItem getItem(int id) {
        return this.plugin.getStorageManager().getExchangeItem(id);
    }

    public List<ExchangeItem> getAllItems() {
        List<ExchangeItem> items = new ArrayList<ExchangeItem>(this.plugin.getStorageManager().getAllExchangeItems());
        items.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return items;
    }

    public ItemStatus getItemStatus(int itemId) {
        return this.plugin.getStorageManager().getItemStatus(itemId);
    }

    public void updateItemStatus(ItemStatus status) {
        this.plugin.getStorageManager().upsertItemStatus(status);
    }

    public BigDecimal getLimitUpPrice(ItemStatus status) {
        if (status == null || !this.plugin.isPriceLimitEnabled()
            || status.getLastClose() == null
            || status.getLastClose().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(this.plugin.getMaxPrice());
        }
        BigDecimal limit = status.getLastClose().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(this.plugin.getLimitUpPercent() / 100.0)));
        return limit.setScale(2, 4);
    }

    public BigDecimal getLimitDownPrice(ItemStatus status) {
        if (status == null || !this.plugin.isPriceLimitEnabled()
            || status.getLastClose() == null
            || status.getLastClose().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(this.plugin.getMinPrice());
        }
        BigDecimal limit = status.getLastClose().multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(this.plugin.getLimitDownPercent() / 100.0)));
        return limit.setScale(2, 4);
    }

    public boolean isPriceWithinLimit(ItemStatus status, BigDecimal price) {
        if (status == null || price == null || !this.plugin.isPriceLimitEnabled()
            || status.getLastClose() == null
            || status.getLastClose().compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        BigDecimal limitUp = this.getLimitUpPrice(status);
        BigDecimal limitDown = this.getLimitDownPrice(status);
        return price.compareTo(limitDown) >= 0 && price.compareTo(limitUp) <= 0;
    }

    public SpecialCategory getSpecialCategory(ExchangeItem item) {
        if (item == null) {
            return null;
        }
        ItemStack stack = ItemSerializer.itemFromBase64(item.getItemBase64());
        return stack == null ? null : this.specialCategoryOf(stack);
    }

    public SpecialCategory specialCategoryOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String value = meta.getPersistentDataContainer().get(
            this.specialCategoryKey,
            PersistentDataType.STRING
        );
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpecialCategory.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public ExchangeItem resolveSpecialItem(ItemStack item) {
        SpecialCategory category = SpecialCategory.of(item);
        return category == null ? null : this.getOrCreateCategoryItem(category);
    }

    public ExchangeItem getOrCreateCategoryItem(SpecialCategory category) {
        if (category == null) {
            return null;
        }
        for (ExchangeItem item : this.plugin.getStorageManager().getAllExchangeItems()) {
            if (category == this.getSpecialCategory(item)) {
                return item;
            }
        }
        for (ExchangeItem item : this.plugin.getStorageManager().getAllExchangeItems()) {
            ItemStack stack = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (stack == null || category != SpecialCategory.of(stack)) {
                continue;
            }
            return this.markCategoryItem(item, category, stack);
        }
        ItemStack representative = category.createRepresentative();
        this.markCategoryItem(representative, category);
        String base64 = ItemSerializer.itemToBase64(representative);
        if (base64 == null) {
            return null;
        }
        ExchangeItem exchangeItem = new ExchangeItem();
        exchangeItem.setMaterial(representative.getType().name());
        exchangeItem.setNbtHash(ItemSerializer.calculateNbtHash(representative));
        exchangeItem.setItemBase64(base64);
        exchangeItem.setDisplayName(category.displayName());
        exchangeItem.setItemName(category.displayName());
        exchangeItem.setItemLore("");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        exchangeItem.setCreatedAt(now);
        exchangeItem.setLastStockedAt(now);
        exchangeItem.setLastEmptyAt(null);
        int id = this.plugin.getStorageManager().insertExchangeItem(exchangeItem);
        if (id <= 0) {
            return null;
        }
        exchangeItem.setId(id);
        ItemStatus status = new ItemStatus();
        status.setItemId(id);
        status.setSuspended(false);
        status.setLastClose(BigDecimal.ZERO);
        status.setLastOpen(BigDecimal.ZERO);
        status.setHighToday(BigDecimal.ZERO);
        status.setLowToday(BigDecimal.ZERO);
        status.setVolumeToday(0);
        status.setLowestSellCurrent(BigDecimal.ZERO);
        status.setLowestSellReference(BigDecimal.ZERO);
        status.setLowestSellReferenceAt(0L);
        status.setLowestSellReference7d(BigDecimal.ZERO);
        status.setLowestSellReferenceAt7d(0L);
        status.setLowestSellReference30d(BigDecimal.ZERO);
        status.setLowestSellReferenceAt30d(0L);
        this.plugin.getStorageManager().upsertItemStatus(status);
        return exchangeItem;
    }

    public void ensureSpecialCategories() {
        for (SpecialCategory category : SpecialCategory.values()) {
            this.getOrCreateCategoryItem(category);
        }
    }

    private ExchangeItem markCategoryItem(ExchangeItem item, SpecialCategory category, ItemStack stack) {
        this.markCategoryItem(stack, category);
        String base64 = ItemSerializer.itemToBase64(stack);
        if (base64 == null) {
            return null;
        }
        item.setItemBase64(base64);
        item.setDisplayName(category.displayName());
        item.setItemName(category.displayName());
        this.plugin.getStorageManager().updateExchangeItem(item);
        return item;
    }

    private void markCategoryItem(ItemStack stack, SpecialCategory category) {
        if (stack == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(
            this.specialCategoryKey,
            PersistentDataType.STRING,
            category.name()
        );
        stack.setItemMeta(meta);
    }

    public static final class RegisterResult {
        private final boolean success;
        private final boolean newlyRegistered;
        private final ExchangeItem item;
        private final String message;

        public RegisterResult(boolean success, boolean newlyRegistered, ExchangeItem item, String message) {
            this.success = success;
            this.newlyRegistered = newlyRegistered;
            this.item = item;
            this.message = message;
        }

        public boolean isSuccess() {
            return this.success;
        }

        public boolean isNewlyRegistered() {
            return this.newlyRegistered;
        }

        public ExchangeItem getItem() {
            return this.item;
        }

        public String getMessage() {
            return this.message;
        }
    }
}
