/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.inventory.InventoryCloseEvent
 *  org.bukkit.event.inventory.InventoryDragEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 */
package com.github.exchange.gui;

import com.github.exchange.StockExchangePlugin;
import cn.gmzc.mgactivitys.MGActivitysPlugin;
import cn.gmzc.mgactivitys.model.ActivityData;
import com.github.exchange.manager.ItemManager;
import com.github.exchange.manager.SupplyPlanner;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.util.InventoryDelivery;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.MarketGuiItem;
import com.github.exchange.util.MarketPageFilter;
import com.github.exchange.util.SpecialCategory;
import com.github.exchange.util.TaxCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class ExchangeGUI
implements Listener {
    private static final Map<UUID, String> guiState = new HashMap<UUID, String>();
    private static final Map<UUID, Integer> guiItemId = new HashMap<UUID, Integer>();
    private static final Map<UUID, Integer> guiPage = new HashMap<UUID, Integer>();
    private static final Map<UUID, Integer> guiDetailPage = new HashMap<UUID, Integer>();
    private static final Map<UUID, Boolean> guiNavigating = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Boolean> bulkBuyMode = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Boolean> buyMode = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Map<Integer, String>> guiWarehouseEntries = new HashMap<UUID, Map<Integer, String>>();
    private static final Map<UUID, String> guiSearchQueries = new HashMap<UUID, String>();
    private static final Map<UUID, Map<String, Integer>> listingPending = new HashMap<UUID, Map<String, Integer>>();
    private static final Map<UUID, BukkitTask> categoryIconRotationTasks = new HashMap<UUID, BukkitTask>();
    private static final Map<UUID, Inventory> categoryIconRotationInventories = new HashMap<UUID, Inventory>();
    private static final String MAIN_MENU = "main";
    private static final String ITEM_LIST = "item_list";
    private static final String ITEM_DETAIL = "item_detail";
    private static final String ORDER_BOOK = "order_book";
    private static final String MY_HISTORY = "my_history";
    private static final String WAREHOUSE = "warehouse";
    private static final String ANNOUNCEMENTS = "announcements";
    private static final String CURRENCY_EXCHANGE = "currency_exchange";
    private static final String ADD_ITEM = "add_item";
    private static final String ADD_BUY_ITEM = "add_buy_item";
    private static final String LISTING = "listing";
    private static final String LISTING_PRICE = "listing_price";
    private static final String MARKET_PAGE_KEY = "market_page";
    private static final String PAGE_PREV = "\u00a7e\u4e0a\u4e00\u9875";
    private static final String PAGE_NEXT = "\u00a7e\u4e0b\u4e00\u9875";
    private static final String BACK_TO_PREVIOUS = "\u00a7f\u8fd4\u56de\u4e0a\u4e00\u9875";
    private static final String SELL_MODE_NAME = "\u00a7a\u51fa\u552e\u5546\u54c1";
    private static final String BUY_MODE_NAME = "\u00a7c\u6c42\u8d2d\u5546\u54c1";
    private static final int ADD_ITEM_INPUT_SLOT = 13;
    private static final int[] LISTING_DISPLAY_SLOTS = new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int LISTING_CONFIRM_SLOT = 18;
    private static final int LISTING_CANCEL_SLOT = 26;
    private static final int ITEM_LIST_PAGE_SIZE = 36;
    private static final int ITEM_LIST_SEPARATOR_START_SLOT = 36;
    private static final int ITEM_LIST_SEPARATOR_END_SLOT = 43;
    private static final int ITEM_LIST_ACTION_SLOT = 44;
    private static final int ITEM_LIST_PREV_SLOT = 51;
    private static final int ITEM_LIST_SEARCH_SLOT = 50;
    private static final int LARGE_PREV_SLOT = 45;
    private static final int LARGE_NEXT_SLOT = 52;
    private static final int LARGE_BACK_SLOT = 53;
    private static final int SMALL_BACK_SLOT = 26;
    private static final int ITEM_DETAIL_PAGE_SIZE = 45;
    private static final int ITEM_DETAIL_PREV_SLOT = 46;
    private static final int ITEM_DETAIL_NEXT_SLOT = 47;
    private static final long CATALOG_QUERY_VISIBILITY_MILLIS = TimeUnit.HOURS.toMillis(24L);

    private static NamespacedKey marketPageKey(StockExchangePlugin plugin) {
        return new NamespacedKey(plugin, MARKET_PAGE_KEY);
    }

    private static NamespacedKey categoryItemIdKey(StockExchangePlugin plugin) {
        return new NamespacedKey(plugin, "category_item_id");
    }

    private static void readMarketPage(StockExchangePlugin plugin, Player player) {
        UUID uuid = player.getUniqueId();
        if (buyMode.containsKey(uuid)) {
            return;
        }
        Byte stored = player.getPersistentDataContainer().get(
            ExchangeGUI.marketPageKey(plugin),
            PersistentDataType.BYTE
        );
        buyMode.put(uuid, stored != null && stored.byteValue() == 1);
    }

    private static void saveMarketPage(StockExchangePlugin plugin, Player player) {
        UUID uuid = player.getUniqueId();
        Boolean isBuy = buyMode.get(uuid);
        if (isBuy == null) {
            return;
        }
        player.getPersistentDataContainer().set(
            ExchangeGUI.marketPageKey(plugin),
            PersistentDataType.BYTE,
            (byte)(isBuy ? 1 : 0)
        );
    }

    private static boolean isBuyPage(StockExchangePlugin plugin, Player player) {
        ExchangeGUI.readMarketPage(plugin, player);
        return buyMode.getOrDefault(player.getUniqueId(), false);
    }

    private static void setBuyPage(StockExchangePlugin plugin, Player player, boolean isBuy) {
        buyMode.put(player.getUniqueId(), isBuy);
        ExchangeGUI.saveMarketPage(plugin, player);
    }

    private static String formatPrice(BigDecimal price) {
        return String.format(Locale.ROOT, "%.2f", price);
    }

    private static String formatHighlightedPrice(BigDecimal price) {
        return "\u00a7e\u00a7l" + ExchangeGUI.formatPrice(price);
    }

    static String formatActivity(double activity) {
        if (!Double.isFinite(activity)) {
            return "0.0";
        }
        return BigDecimal.valueOf(activity).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatSigned(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        int cmp = v.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            return "+" + ExchangeGUI.formatPrice(v);
        }
        return ExchangeGUI.formatPrice(v);
    }

    private static String formatChangeText(BigDecimal change) {
        if (change == null) {
            return "\u00a77\u6682\u65e0";
        }
        int compare = change.compareTo(BigDecimal.ZERO);
        String color = compare > 0 ? "\u00a7c" : (compare < 0 ? "\u00a7a" : "\u00a7f");
        return color + ExchangeGUI.formatSigned(change) + "%";
    }

    private static BigDecimal getWindowedChangePercent(StockExchangePlugin plugin, int itemId, int days) {
        BigDecimal currentLowest = plugin.getOrderManager().getLowestSellPrice(itemId);
        if (currentLowest == null || currentLowest.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            return null;
        }
        BigDecimal referenceLowest;
        if (days >= 30) {
            referenceLowest = status.getLowestSellReference30d();
        } else if (days >= 7) {
            referenceLowest = status.getLowestSellReference7d();
        } else {
            referenceLowest = status.getLowestSellReference();
        }
        if (referenceLowest == null || referenceLowest.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return currentLowest.subtract(referenceLowest).divide(referenceLowest, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100L)).setScale(2, RoundingMode.HALF_UP);
    }

    private static MarketSnapshot buildSnapshot(StockExchangePlugin plugin, int itemId) {
        MarketSnapshot s = new MarketSnapshot();
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1L);
        Trade yLast = plugin.getStorageManager().getLastTradeOfDate(itemId, yesterday);
        Trade tFirst = plugin.getStorageManager().getFirstTradeOfDate(itemId, today);
        Trade last = plugin.getTradeManager().getLastTrade(itemId);
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (yLast != null && yLast.getPrice() != null) {
            s.yesterdayClose = yLast.getPrice();
        }
        if (tFirst != null && tFirst.getPrice() != null) {
            s.todayOpen = tFirst.getPrice();
        }
        s.currentPrice = last != null && last.getPrice() != null ? last.getPrice() : (s.todayOpen.compareTo(BigDecimal.ZERO) > 0 ? s.todayOpen : s.yesterdayClose);
        if (status != null) {
            s.high = status.getHighToday() != null ? status.getHighToday() : BigDecimal.ZERO;
            BigDecimal bigDecimal = s.low = status.getLowToday() != null ? status.getLowToday() : BigDecimal.ZERO;
        }
        if (s.yesterdayClose.compareTo(BigDecimal.ZERO) > 0) {
            s.changePercent = s.currentPrice.divide(s.yesterdayClose, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100L)).setScale(2, RoundingMode.HALF_UP);
        }
        return s;
    }

    private static String colorByCompare(BigDecimal value, BigDecimal base) {
        if (value == null || base == null) {
            return "\u00a7f";
        }
        int cmp = value.compareTo(base);
        if (cmp > 0) {
            return "\u00a7c";
        }
        if (cmp < 0) {
            return "\u00a7a";
        }
        return "\u00a7f";
    }

    public static void openMainMenu(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openItemList(plugin, player);
    }

    public static void openItemList(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.readMarketPage(plugin, player);
        guiSearchQueries.remove(player.getUniqueId());
        ExchangeGUI.openItemList(plugin, player, 1);
    }

    private static void openItemList(StockExchangePlugin plugin, Player player, int page) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.readMarketPage(plugin, player);
        if (ExchangeGUI.isBuyPage(plugin, player)) {
            ExchangeGUI.openBuyOrderList(plugin, player, page);
        } else {
            ExchangeGUI.openCategoryList(plugin, player, page);
        }
    }

    private static void openBuyOrderList(StockExchangePlugin plugin, Player player, int page) {
        String query = guiSearchQueries.get(player.getUniqueId());
        List<MarketListingLayout.Slot> listingSlots = ExchangeGUI.collectBuyOrderSlots(plugin, query);
        String title = "\u00a76\u4ea4\u6613\u5e02\u573a - \u6c42\u8d2d\u5546\u54c1"
            + (query == null ? "" : " - \u641c\u7d22\u7ed3\u679c");
        Inventory inv = Bukkit.createInventory(null, 54, title);
        int pageSize = ITEM_LIST_PAGE_SIZE;
        int totalPages = MarketListingLayout.pageCount(listingSlots, pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, listingSlots.size());
        int slot = 0;
        for (int index = start; index < end; ++index) {
            MarketListingLayout.Slot listingSlot = listingSlots.get(index);
            Order buyOrder = listingSlot.order();
            ExchangeItem item = plugin.getItemManager().getItem(buyOrder.getItemId());
            if (item == null) {
                continue;
            }
            ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (baseItem == null) {
                continue;
            }
            String itemDisplayName = ExchangeGUI.resolveDisplayName(item, baseItem);
            int displayedQuantity = listingSlot.amount();
            ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, itemDisplayName);
            ExchangeGUI.setDisplayAmount(displayItem, displayedQuantity);
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                ArrayList<String> lore = new ArrayList<String>();
                lore.add("\u00a77\u7269\u54c1: \u00a7f" + itemDisplayName);
                lore.add("\u00a77\u4e70\u5bb6: \u00a7f" + safeText(buyOrder.getPlayerName(), "\u672a\u77e5\u73a9\u5bb6"));
                lore.add("\u00a77\u6c42\u8d2d\u4ef7: " + ExchangeGUI.formatHighlightedPrice(buyOrder.getPrice()));
                lore.add("\u00a77\u8fd9\u683c\u6570\u91cf: \u00a7f" + displayedQuantity);
                lore.add("\u00a77\u8be5\u6c42\u8d2d\u5355\u5269\u4f59: \u00a7f" + buyOrder.getRemainingQty());
                lore.add("");
                boolean ownOrder = buyOrder.getPlayerUuid().equals(player.getUniqueId().toString());
                if (ownOrder) {
                    meta.setEnchantmentGlintOverride(true);
                    lore.add("\u00a7e\u5de6\u952e: \u51cf\u5c11 1 \u4e2a\u6c42\u8d2d");
                    lore.add("\u00a7eShift+\u5de6\u952e: \u53d6\u6d88\u672c\u683c\u5168\u90e8\u6c42\u8d2d");
                } else {
                    lore.add("\u00a7e\u5de6\u952e: \u4f9b\u8d27 1 \u4e2a");
                    lore.add("\u00a7eShift+\u5de6\u952e: \u4f9b\u8d27\u672c\u683c " + displayedQuantity
                        + " \u4e2a\uff08\u80cc\u5305\u4e0d\u8db3\u5219\u4f9b\u8d27\u73b0\u6709\u6570\u91cf\uff09");
                }
                lore.add("\u00a70ORDER:" + buyOrder.getId());
                meta.setLore(lore);
                displayItem.setItemMeta(meta);
            }
            inv.setItem(slot++, displayItem);
        }
        if (listingSlots.isEmpty()) {
            inv.setItem(22, ExchangeGUI.createItem(
                Material.BARRIER,
                "\u00a7c\u6682\u65e0\u6c42\u8d2d\u4e2d\u7684\u5546\u54c1",
                query == null
                    ? "\u00a77\u5f53\u524d\u6ca1\u6709\u6d3b\u52a8\u6c42\u8d2d\u5355"
                    : "\u00a77\u641c\u7d22\u5173\u952e\u8bcd: \u00a7f" + ExchangeGUI.safeQueryForDisplay(query)
            ));
        }
        inv.setItem(ITEM_LIST_ACTION_SLOT, ExchangeGUI.createItem(
            Material.EMERALD,
            "\u00a7c\u6c42\u8d2d\u7269\u54c1",
            "\u00a77\u9009\u62e9\u7269\u54c1\u5e76\u53d1\u5e03\u6c42\u8d2d\u5355",
            "\u00a77\u4e0d\u4f1a\u6d88\u8017\u80cc\u5305\u7269\u54c1"
        ));
        ItemStack separator = ExchangeGUI.createItem(
            new ItemStack(Material.ORANGE_STAINED_GLASS_PANE),
            "\u00a77",
            new String[]{null}
        );
        for (
            int separatorSlot = ITEM_LIST_SEPARATOR_START_SLOT;
            separatorSlot <= ITEM_LIST_SEPARATOR_END_SLOT;
            ++separatorSlot
        ) {
            inv.setItem(separatorSlot, separator);
        }
        ExchangeGUI.populateMarketFooter(plugin, player, inv, currentPage, totalPages);
        ExchangeGUI.finishOpeningItemList(
            plugin,
            player,
            inv,
            currentPage,
            new HashMap<Integer, CategoryIconRotationSlot>()
        );
    }

    private static List<MarketListingLayout.Slot> collectBuyOrderSlots(
        StockExchangePlugin plugin,
        String query
    ) {
        List<Order> buyOrders = new ArrayList<Order>();
        for (ExchangeItem item : plugin.getItemManager().getAllItems()) {
            if (item == null || (query != null && !ExchangeGUI.matchesCatalogSearch(plugin, item, query))) {
                continue;
            }
            buyOrders.addAll(plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.BUY));
        }
        List<MarketListingLayout.Slot> slots = new ArrayList<MarketListingLayout.Slot>();
        for (Order buyOrder : MarketListingLayout.sortBuyOrders(buyOrders)) {
            ExchangeItem item = plugin.getItemManager().getItem(buyOrder.getItemId());
            if (item == null) {
                continue;
            }
            ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (baseItem == null) {
                continue;
            }
            slots.addAll(MarketListingLayout.expand(buyOrder, baseItem.getMaxStackSize()));
        }
        return slots;
    }

    private static void openCategoryList(StockExchangePlugin plugin, Player player, int page) {
        String query = guiSearchQueries.get(player.getUniqueId());
        boolean explicitSearch = query != null && !query.isBlank();
        boolean isBuy = ExchangeGUI.isBuyPage(plugin, player);
        plugin.getItemManager().ensureSpecialCategories();
        List<ExchangeItem> items = new ArrayList<ExchangeItem>(plugin.getItemManager().getAllItems());
        long now = System.currentTimeMillis();
        Order.OrderType pageOrderType = isBuy ? Order.OrderType.BUY : Order.OrderType.SELL;
        Map<Integer, Long> activeQuantities = new HashMap<Integer, Long>();
        Map<Integer, Long> latestOrderCreatedAt = new HashMap<Integer, Long>();
        Map<Integer, BigDecimal> activeSellMarketValues = new HashMap<Integer, BigDecimal>();
        items.removeIf(item -> {
            SpecialCategory category = plugin.getItemManager().getSpecialCategory(item);
            if (category != null) {
                List<Order> activeOrders = plugin.getOrderManager().getActiveOrders(item.getId(), pageOrderType);
                long activeQuantity = MarketPageFilter.activeRemainingQuantity(
                    activeOrders
                );
                activeQuantities.put(item.getId(), activeQuantity);
                latestOrderCreatedAt.put(
                    item.getId(),
                    plugin.getStorageManager().getLatestOrderCreatedAt(item.getId(), pageOrderType)
                );
                if (!isBuy) {
                    activeSellMarketValues.put(
                        item.getId(),
                        MarketPageFilter.activeSellMarketValue(activeOrders)
                    );
                }
                return false;
            }
            ItemStack rawItemStack = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (rawItemStack != null && SpecialCategory.of(rawItemStack) != null) {
                return true;
            }
            List<Order> activeOrders = plugin.getOrderManager().getActiveOrders(item.getId(), pageOrderType);
            long activeQuantity = MarketPageFilter.activeRemainingQuantity(activeOrders);
            long latestOrderAt = plugin.getStorageManager().getLatestOrderCreatedAt(item.getId(), pageOrderType);
            activeQuantities.put(item.getId(), activeQuantity);
            latestOrderCreatedAt.put(item.getId(), latestOrderAt);
            if (!isBuy) {
                activeSellMarketValues.put(
                    item.getId(),
                    MarketPageFilter.activeSellMarketValue(activeOrders)
                );
            }
            if (!isBuy) {
                long sellCatalogActivityAt = item.getLastSellCatalogActivityAt() == null
                    ? 0L
                    : item.getLastSellCatalogActivityAt().getTime();
                return !MarketPageFilter.isVisibleOnSellPage(
                    activeQuantity,
                    latestOrderAt,
                    sellCatalogActivityAt,
                    now,
                    MarketPageFilter.SELL_CATALOG_VISIBILITY_MILLIS
                );
            }
            return !MarketPageFilter.isVisibleForQuery(
                activeQuantity,
                latestOrderAt,
                now,
                CATALOG_QUERY_VISIBILITY_MILLIS,
                explicitSearch
            );
        });
        if (isBuy) {
            items.sort((a, b) -> {
                int cmp = Long.compare(
                    activeQuantities.getOrDefault(b.getId(), 0L),
                    activeQuantities.getOrDefault(a.getId(), 0L)
                );
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Long.compare(
                    latestOrderCreatedAt.getOrDefault(b.getId(), 0L),
                    latestOrderCreatedAt.getOrDefault(a.getId(), 0L)
                );
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a.getId(), b.getId());
            });
        } else {
            items.sort((a, b) -> MarketPageFilter.compareSellCatalogEntries(
                    activeSellMarketValues.get(a.getId()),
                    latestOrderCreatedAt.getOrDefault(a.getId(), 0L),
                    a.getId(),
                    activeSellMarketValues.get(b.getId()),
                    latestOrderCreatedAt.getOrDefault(b.getId(), 0L),
                    b.getId()
                )
            );
        }
        if (query != null) {
            items.removeIf(item -> plugin.getItemManager().getSpecialCategory(item) == null
                && !ExchangeGUI.matchesCatalogSearch(plugin, item, query));
        }
        String pageName = isBuy ? "\u6c42\u8d2d\u5546\u54c1" : "\u51fa\u552e\u5546\u54c1";
        String title = "\u00a76\u4ea4\u6613\u5e02\u573a - " + pageName
            + (query == null ? "" : " - \u641c\u7d22\u7ed3\u679c");
        Inventory inv = Bukkit.createInventory(null, 54, title);
        int pageSize = ITEM_LIST_PAGE_SIZE;
        int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, items.size());
        int slot = 0;
        Map<Integer, CategoryIconRotationSlot> categoryIconRotationSlots =
            new HashMap<Integer, CategoryIconRotationSlot>();
        for (int idx = start; idx < end; ++idx) {
            ExchangeItem item = items.get(idx);
            ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (baseItem == null) continue;
            String displayName = ExchangeGUI.resolveDisplayName(item, baseItem);
            SpecialCategory specialCategory = plugin.getItemManager().getSpecialCategory(item);
            if (specialCategory != null) {
                long stock = activeQuantities.getOrDefault(item.getId(), 0L);
                List<ItemStack> iconItems = isBuy
                    ? new ArrayList<ItemStack>()
                    : ExchangeGUI.collectSpecialCategorySellIcons(plugin, item, specialCategory);
                List<ItemStack> displayVariants = ExchangeGUI.createSpecialCategoryDisplayVariants(
                    plugin,
                    item,
                    baseItem,
                    displayName,
                    stock,
                    iconItems
                );
                ItemStack displayItem = displayVariants.get(0);
                if (!isBuy && stock > 0L) {
                    categoryIconRotationSlots.put(
                        slot,
                        new CategoryIconRotationSlot(item.getId(), specialCategory)
                    );
                }
                inv.setItem(slot++, displayItem);
                continue;
            }
            ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, displayName);
            ItemMeta meta = displayItem.getItemMeta();
            if (isBuy) {
                BigDecimal highestBuy = plugin.getOrderManager().getHighestBuyPrice(item.getId());
                long buyStock = activeQuantities.getOrDefault(item.getId(), 0L);
                String buyPriceText = highestBuy == null ? "\u00a77\u6682\u65e0" : ExchangeGUI.formatHighlightedPrice(highestBuy);
                meta.setDisplayName("\u00a7f" + displayName);
                ArrayList<String> lore = new ArrayList<String>();
                lore.add("\u00a77ID: \u00a7f" + item.getId());
                lore.add("\u00a77\u6c42\u8d2d\u6700\u9ad8\u4ef7: " + buyPriceText);
                lore.add("\u00a77\u6c42\u8d2d\u6302\u5355\u91cf: \u00a7f" + buyStock);
                lore.add("");
                lore.add("\u00a7e\u70b9\u51fb\u67e5\u770b\u5546\u54c1\u8be6\u60c5");
                meta.setLore(lore);
            } else {
                BigDecimal lowestPrice = plugin.getOrderManager().getLowestSellPrice(item.getId());
                long stock = activeQuantities.getOrDefault(item.getId(), 0L);
                BigDecimal sevenDayChange = ExchangeGUI.getWindowedChangePercent(plugin, item.getId(), 7);
                BigDecimal monthChange = ExchangeGUI.getWindowedChangePercent(plugin, item.getId(), 30);
                String lowestPriceText = lowestPrice == null ? "\u00a77\u6682\u65e0" : ExchangeGUI.formatHighlightedPrice(lowestPrice);
                String sevenDayText = ExchangeGUI.formatChangeText(sevenDayChange);
                String monthText = ExchangeGUI.formatChangeText(monthChange);
                meta.setDisplayName("\u00a7f" + displayName);
                ArrayList<String> lore = new ArrayList<String>();
                lore.add("\u00a77ID: \u00a7f" + item.getId());
                lore.add("\u00a77\u8fd1\u4e03\u5929\u6da8\u5e45: " + sevenDayText);
                lore.add("\u00a77\u8fd1\u4e00\u4e2a\u6708\u6da8\u5e45: " + monthText);
                lore.add("\u00a77\u73b0\u5b58\u8d27\u91cf: \u00a7f" + stock);
                lore.add("\u00a77\u6700\u4f4e\u4ef7: " + lowestPriceText);
                lore.add("");
                lore.add("\u00a7e\u70b9\u51fb\u67e5\u770b\u5546\u54c1\u8be6\u60c5");
                meta.setLore(lore);
            }
            displayItem.setItemMeta(meta);
            inv.setItem(slot++, displayItem);
        }
        if (items.isEmpty()) {
            inv.setItem(22, ExchangeGUI.createItem(
                Material.BARRIER,
                isBuy
                    ? "\u00a7c\u6682\u65e0\u6c42\u8d2d\u4e2d\u7684\u5546\u54c1"
                    : "\u00a7c\u6682\u65e0\u51fa\u552e\u4e2d\u7684\u5546\u54c1",
                query == null
                    ? "\u00a77\u5f53\u524d\u6ca1\u6709\u53ef\u663e\u793a\u7684\u54c1\u79cd"
                    : "\u00a77\u641c\u7d22\u5173\u952e\u8bcd: \u00a7f" + ExchangeGUI.safeQueryForDisplay(query)
            ));
        }
        // Keep the page-specific action at the end of the fifth row.
        inv.setItem(ITEM_LIST_ACTION_SLOT, isBuy
            ? ExchangeGUI.createItem(
                Material.EMERALD,
                "\u00a7c\u6c42\u8d2d\u7269\u54c1",
                "\u00a77\u9009\u62e9\u7269\u54c1\u5e76\u53d1\u5e03\u6c42\u8d2d\u5355",
                "\u00a77\u4e0d\u4f1a\u6d88\u8017\u80cc\u5305\u7269\u54c1"
            )
            : ExchangeGUI.createItem(
                Material.EMERALD,
                "\u00a7a\u4e0a\u67b6\u5546\u54c1",
                "\u00a77\u6253\u5f00\u4e0a\u67b6\u83dc\u5355\uff0c\u653e\u5165\u7269\u54c1\u540e\u8f93\u5165\u5355\u4ef7\u7edf\u4e00\u4e0a\u67b6",
                "\u00a77\u53d6\u6d88\u6216\u9000\u51fa\u65f6\u653e\u5165\u7684\u7269\u54c1\u4f1a\u5168\u90e8\u8fd8\u8fd8"
            ));
        Material separatorMaterial = isBuy
            ? Material.ORANGE_STAINED_GLASS_PANE
            : Material.GREEN_STAINED_GLASS_PANE;
        ItemStack separator = ExchangeGUI.createItem(new ItemStack(separatorMaterial), "\u00a77", new String[]{null});
        for (
            int separatorSlot = ITEM_LIST_SEPARATOR_START_SLOT;
            separatorSlot <= ITEM_LIST_SEPARATOR_END_SLOT;
            ++separatorSlot
        ) {
            inv.setItem(separatorSlot, separator);
        }
        ExchangeGUI.populateMarketFooter(plugin, player, inv, currentPage, totalPages);
        ExchangeGUI.finishOpeningItemList(plugin, player, inv, currentPage, categoryIconRotationSlots);
    }

    public static void openCatalogSearchResults(
        StockExchangePlugin plugin,
        Player player,
        String query,
        int page
    ) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            ExchangeGUI.openItemList(plugin, player);
            return;
        }
        guiSearchQueries.put(player.getUniqueId(), trimmedQuery);
        ExchangeGUI.openItemList(plugin, player, page);
    }

    private static void populateMarketFooter(
        StockExchangePlugin plugin,
        Player player,
        Inventory inv,
        int currentPage,
        int totalPages
    ) {
        inv.setItem(ITEM_LIST_PREV_SLOT, ExchangeGUI.navigationItem(
            PAGE_PREV,
            currentPage > 1,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        List<String> activityLore = new ArrayList<String>();
        activityLore.add("\u00a77\u70b9\u51fb\u6253\u5f00\u6210\u957f\u5546\u5e97");
        MGActivitysPlugin actiPlugin = null;
        try {
            actiPlugin = MGActivitysPlugin.getInstance();
        } catch (Throwable ignore) {}
        if (actiPlugin != null) {
            try {
                ActivityData ad = actiPlugin.getActivityManager().getPlayerData(player.getName());
                activityLore.add("\u00a7e\u603b\u6210\u957f\u503c: \u00a7a" + ExchangeGUI.formatActivity(ad.getTotalActivity()));
                activityLore.add("\u00a7e\u52a8\u6001\u6210\u957f\u503c: \u00a7a" + ExchangeGUI.formatActivity(ad.getDynamicActivity()));
            } catch (Throwable ignore) {}
        }
        inv.setItem(45, ExchangeGUI.createItem(Material.EXPERIENCE_BOTTLE, "\u00a7d\u6210\u957f\u5546\u5e97", activityLore.toArray(new String[0])));
        boolean modeIsBuy = buyMode.getOrDefault(player.getUniqueId(), false);
        String currentModeName = modeIsBuy ? BUY_MODE_NAME : SELL_MODE_NAME;
        String nextModeName = modeIsBuy ? SELL_MODE_NAME : BUY_MODE_NAME;
        inv.setItem(46, ExchangeGUI.createItem(
            Material.COMPARATOR,
            currentModeName + "\u00a7f\u9875\u9762",
            "\u00a77\u5f53\u524d\u4f4d\u4e8e\uff1a" + (modeIsBuy ? "\u00a7c\u6c42\u8d2d\u5546\u54c1\u9875\u9762" : "\u00a7a\u51fa\u552e\u5546\u54c1\u9875\u9762"),
            "\u00a7e\u70b9\u51fb\u5207\u6362\u5230" + nextModeName + "\u00a7f\u9875\u9762"
        ));
        inv.setItem(47, ExchangeGUI.createItem(
            Material.DIAMOND,
            "\u00a7b\u8d27\u5e01\u5151\u6362",
            "\u00a771 \u94bb\u77f3 <-> " + plugin.getDiamondToMoneyAmount().toPlainString() + " " + plugin.getCurrencyName(),
            "\u00a77\u5f53\u524d\u7a0e\u7387: \u00a7f" + plugin.getTaxRatePercent().toPlainString() + "%"
        ));
        inv.setItem(48, ExchangeGUI.createItem(Material.BOOK, "\u00a7e\u6211\u7684\u4ea4\u6613\u8bb0\u5f55", "\u00a77\u67e5\u770b\u4f60\u7684\u8fdb\u884c\u4e2d\u6302\u5355\u4e0e\u5386\u53f2\u6210\u4ea4"));
        inv.setItem(49, ExchangeGUI.createItem(Material.OAK_SIGN, "\u00a76\u516c\u544a\u680f", "\u00a77\u67e5\u770b\u5168\u90e8\u4ea4\u6613\u6240\u516c\u544a"));
        String query = guiSearchQueries.get(player.getUniqueId());
        inv.setItem(ITEM_LIST_SEARCH_SLOT, ExchangeGUI.createItem(
            Material.COMPASS,
            "\u00a7b\u641c\u7d22\u5546\u54c1",
            query == null
                ? "\u00a77\u6309\u7269\u54c1\u540d\u79f0\u6216 ID \u4e2d\u7684\u5173\u952e\u8bcd\u641c\u7d22"
                : "\u00a77\u5f53\u524d\u5173\u952e\u8bcd: \u00a7f" + ExchangeGUI.safeQueryForDisplay(query),
            "\u00a77\u70b9\u51fb\u8f93\u5165\u65b0\u7684\u641c\u7d22\u5173\u952e\u8bcd"
        ));
        inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u529f\u80fd\u4e3b\u83dc\u5355"));
        inv.setItem(LARGE_NEXT_SLOT, ExchangeGUI.navigationItem(
            PAGE_NEXT,
            currentPage < totalPages,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
    }

    private static void finishOpeningItemList(
        StockExchangePlugin plugin,
        Player player,
        Inventory inv,
        int currentPage,
        Map<Integer, CategoryIconRotationSlot> categoryIconRotationSlots
    ) {
        UUID uuid = player.getUniqueId();
        ExchangeGUI.cancelCategoryIconRotation(uuid, null);
        guiState.put(uuid, ITEM_LIST);
        guiItemId.remove(uuid);
        guiPage.put(uuid, currentPage);
        player.openInventory(inv);
        ExchangeGUI.startCategoryIconRotation(
            plugin,
            player,
            inv,
            categoryIconRotationSlots
        );
    }

    private static void startCategoryIconRotation(
        StockExchangePlugin plugin,
        Player player,
        Inventory inv,
        Map<Integer, CategoryIconRotationSlot> categoryIconRotationSlots
    ) {
        if (categoryIconRotationSlots == null || categoryIconRotationSlots.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long openedAt = System.currentTimeMillis();
        BukkitTask[] taskHolder = new BukkitTask[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Inventory rotatingInventory = categoryIconRotationInventories.get(uuid);
            if (!player.isOnline()
                || rotatingInventory != inv
                || !ITEM_LIST.equals(guiState.get(uuid))
                || player.getOpenInventory().getTopInventory() != inv) {
                if (rotatingInventory == inv) {
                    ExchangeGUI.cancelCategoryIconRotation(uuid, inv);
                } else if (taskHolder[0] != null) {
                    taskHolder[0].cancel();
                }
                return;
            }
            long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - openedAt) / 1000L);
            for (Map.Entry<Integer, CategoryIconRotationSlot> entry : categoryIconRotationSlots.entrySet()) {
                ExchangeGUI.refreshSpecialCategoryIcon(
                    plugin,
                    inv,
                    entry.getKey(),
                    entry.getValue(),
                    elapsedSeconds
                );
            }
        }, 20L, 20L);
        taskHolder[0] = task;
        categoryIconRotationInventories.put(uuid, inv);
        categoryIconRotationTasks.put(uuid, task);
    }

    private static void refreshSpecialCategoryIcon(
        StockExchangePlugin plugin,
        Inventory inv,
        int slot,
        CategoryIconRotationSlot rotationSlot,
        long elapsedSeconds
    ) {
        if (rotationSlot == null) {
            return;
        }
        ExchangeItem categoryItem = plugin.getItemManager().getItem(rotationSlot.categoryItemId);
        if (categoryItem == null) {
            return;
        }
        ItemStack categoryBase = ItemSerializer.itemFromBase64(categoryItem.getItemBase64());
        if (categoryBase == null) {
            return;
        }
        List<Order> sellOrders = plugin.getOrderManager().getActiveOrders(
            categoryItem.getId(),
            Order.OrderType.SELL
        );
        long stock = MarketPageFilter.activeRemainingQuantity(sellOrders);
        List<ItemStack> displayVariants = ExchangeGUI.createSpecialCategoryDisplayVariants(
            plugin,
            categoryItem,
            categoryBase,
            ExchangeGUI.resolveDisplayName(categoryItem, categoryBase),
            stock,
            ExchangeGUI.collectSpecialCategorySellIcons(
                plugin,
                categoryItem,
                rotationSlot.category
            )
        );
        int displayIndex = MarketCategoryIconRotation.indexForSecond(
            elapsedSeconds,
            displayVariants.size()
        );
        if (displayIndex >= 0) {
            inv.setItem(slot, displayVariants.get(displayIndex));
        }
    }

    private static List<ItemStack> collectSpecialCategorySellIcons(
        StockExchangePlugin plugin,
        ExchangeItem categoryItem,
        SpecialCategory category
    ) {
        List<String> iconBase64s = new ArrayList<String>();
        if (categoryItem == null || category == null) {
            return new ArrayList<ItemStack>();
        }
        List<Order> sellOrders = new ArrayList<Order>(
            plugin.getOrderManager().getActiveOrders(categoryItem.getId(), Order.OrderType.SELL)
        );
        sellOrders.sort(Comparator.comparingInt(Order::getId));
        for (Order sellOrder : sellOrders) {
            if (sellOrder == null
                || sellOrder.getOrderType() != Order.OrderType.SELL
                || !sellOrder.isActiveForCalculation()
                || sellOrder.getRemainingQty() <= 0) {
                continue;
            }
            EscrowEntry escrow = plugin.getStorageManager().getEscrow(
                sellOrder.getId(),
                EscrowEntry.AssetType.ITEM
            );
            if (escrow == null
                || escrow.getQuantity() <= 0
                || escrow.getItemBase64() == null
                || escrow.getItemBase64().isBlank()) {
                continue;
            }
            ItemStack actualItem = ItemSerializer.itemFromBase64(escrow.getItemBase64());
            if (SpecialCategory.of(actualItem) == category) {
                actualItem.setAmount(1);
                iconBase64s.add(ItemSerializer.itemToBase64(actualItem));
            }
        }
        List<ItemStack> icons = new ArrayList<ItemStack>();
        for (String itemBase64 : MarketCategoryIconRotation.distinctStableIconKeys(iconBase64s)) {
            ItemStack icon = ItemSerializer.itemFromBase64(itemBase64);
            if (icon != null && icon.getType() != Material.AIR) {
                icon.setAmount(1);
                icons.add(icon);
            }
        }
        return icons;
    }

    private static List<ItemStack> createSpecialCategoryDisplayVariants(
        StockExchangePlugin plugin,
        ExchangeItem categoryItem,
        ItemStack categoryBase,
        String displayName,
        long stock,
        List<ItemStack> iconItems
    ) {
        List<ItemStack> displayVariants = new ArrayList<ItemStack>();
        if (iconItems != null) {
            for (ItemStack iconItem : iconItems) {
                if (iconItem != null && iconItem.getType() != Material.AIR) {
                    displayVariants.add(ExchangeGUI.createSpecialCategoryDisplayItem(
                        plugin,
                        categoryItem,
                        iconItem,
                        displayName,
                        stock
                    ));
                }
            }
        }
        if (displayVariants.isEmpty()) {
            displayVariants.add(ExchangeGUI.createSpecialCategoryDisplayItem(
                plugin,
                categoryItem,
                categoryBase,
                displayName,
                stock
            ));
        }
        return displayVariants;
    }

    private static ItemStack createSpecialCategoryDisplayItem(
        StockExchangePlugin plugin,
        ExchangeItem categoryItem,
        ItemStack iconItem,
        String displayName,
        long stock
    ) {
        ItemStack displayItem = ExchangeGUI.createMarketVoucher(iconItem, displayName);
        ItemMeta meta = displayItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a7f" + displayName);
            ArrayList<String> lore = new ArrayList<String>();
            lore.add("\u00a77\u5b58\u8d27\u91cf: \u00a7f" + stock);
            lore.add("");
            lore.add("\u00a7e\u70b9\u51fb\u67e5\u770b\u5546\u54c1\u8be6\u60c5");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(
                ExchangeGUI.categoryItemIdKey(plugin),
                PersistentDataType.INTEGER,
                categoryItem.getId()
            );
            displayItem.setItemMeta(meta);
        }
        return displayItem;
    }

    private static void cancelCategoryIconRotation(UUID uuid, Inventory expectedInventory) {
        Inventory rotatingInventory = categoryIconRotationInventories.get(uuid);
        if (expectedInventory != null && rotatingInventory != expectedInventory) {
            return;
        }
        categoryIconRotationInventories.remove(uuid);
        BukkitTask task = categoryIconRotationTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private static final class CategoryIconRotationSlot {
        private final int categoryItemId;
        private final SpecialCategory category;

        private CategoryIconRotationSlot(int categoryItemId, SpecialCategory category) {
            this.categoryItemId = categoryItemId;
            this.category = category;
        }
    }

    public static void openCurrencyExchangeMenu(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, "\u00a76\u8d27\u5e01\u5151\u6362");
        BigDecimal tax = TaxCalculator.tax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        BigDecimal received = TaxCalculator.afterTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        BigDecimal totalCost = TaxCalculator.withTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        inv.setItem(11, ExchangeGUI.createItem(
            Material.DIAMOND,
            "\u00a7b\u4f7f\u7528 1 \u94bb\u77f3\u5151\u6362 " + received.toPlainString() + " " + plugin.getCurrencyName(),
            "\u00a77\u57fa\u7840\u5151\u6362\u989d: \u00a7f" + plugin.getDiamondToMoneyAmount().toPlainString(),
            "\u00a77\u7a0e\u989d: \u00a7f" + tax.toPlainString() + " \u00a78(" + plugin.getTaxRatePercent().toPlainString() + "%)",
            "\u00a77\u6bcf\u70b9\u51fb\u4e00\u6b21\u6267\u884c\u4e00\u6b21"
        ));
        inv.setItem(15, ExchangeGUI.createItem(
            Material.GOLD_INGOT,
            "\u00a7e\u4f7f\u7528 " + totalCost.toPlainString() + " " + plugin.getCurrencyName() + "\u5151\u6362 1 \u94bb\u77f3",
            "\u00a77\u57fa\u7840\u4ef7: \u00a7f" + plugin.getDiamondToMoneyAmount().toPlainString(),
            "\u00a77\u7a0e\u989d: \u00a7f" + tax.toPlainString() + " \u00a78(" + plugin.getTaxRatePercent().toPlainString() + "%)",
            "\u00a77\u6bcf\u70b9\u51fb\u4e00\u6b21\u6267\u884c\u4e00\u6b21"
        ));
        inv.setItem(SMALL_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u4e3b\u83dc\u5355"));
        guiState.put(player.getUniqueId(), CURRENCY_EXCHANGE);
        guiItemId.remove(player.getUniqueId());
        guiPage.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    public static void openAddItemMenu(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openAddItemMenu(plugin, player, guiPage.getOrDefault(player.getUniqueId(), 1));
    }

    public static void openAddItemMenu(StockExchangePlugin plugin, Player player, int sourcePage) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openItemSelectionMenu(
            plugin,
            player,
            sourcePage,
            ADD_ITEM,
            "\u6dfb\u52a0\u5546\u54c1",
            Material.EMERALD,
            "\u6dfb\u52a0\u8bf4\u660e",
            "\u5c06\u8981\u65b0\u589e\u7684\u7269\u54c1\u653e\u5165\u4e0b\u65b9\u8f93\u5165\u69fd",
            "\u53ea\u4f1a\u628a\u8be5\u7269\u54c1\u52a0\u5165\u5e02\u573a\u76ee\u5f55\uff0c\u4e0d\u4f1a\u6d88\u8017\u80cc\u5305\u7269\u54c1"
        );
    }

    public static void openAddBuyItemMenu(StockExchangePlugin plugin, Player player, int sourcePage) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openItemSelectionMenu(
            plugin,
            player,
            sourcePage,
            ADD_BUY_ITEM,
            "\u6c42\u8d2d\u7269\u54c1",
            Material.REDSTONE,
            "\u6c42\u8d2d\u8bf4\u660e",
            "\u5c06\u8981\u6c42\u8d2d\u7684\u7269\u54c1\u653e\u5165\u4e0b\u65b9\u8f93\u5165\u69fd",
            "\u9009\u62e9\u540e\u4f1a\u76f4\u63a5\u8fdb\u5165\u4ef7\u683c\u548c\u6570\u91cf\u8f93\u5165"
        );
    }

    public static void openAddBuyItemMenu(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openAddBuyItemMenu(plugin, player, guiPage.getOrDefault(player.getUniqueId(), 1));
    }

    private static void openItemSelectionMenu(
        StockExchangePlugin plugin,
        Player player,
        int sourcePage,
        String state,
        String title,
        Material infoMaterial,
        String infoTitle,
        String infoLine,
        String infoLine2
    ) {
        Inventory inv = Bukkit.createInventory(null, 27, "\u00a76" + title);
        inv.setItem(4, ExchangeGUI.createItem(
            infoMaterial,
            "\u00a7a" + infoTitle,
            "\u00a77" + infoLine,
            "\u00a77" + infoLine2
        ));
        inv.setItem(ADD_ITEM_INPUT_SLOT, null);
        inv.setItem(SMALL_BACK_SLOT, ExchangeGUI.createItem(
            Material.ARROW,
            BACK_TO_PREVIOUS,
            "\u00a77\u8fd4\u56de\u5546\u54c1\u9875\u9762"
        ));
        if (ADD_ITEM.equals(state) || ADD_BUY_ITEM.equals(state)) {
            inv.setItem(22, ExchangeGUI.createItem(
                Material.NAME_TAG,
                "\u00a7e\u641c\u7d22\u6dfb\u52a0",
                "\u00a77\u8f93\u5165\u7269\u54c1\u540d\u79f0\u6216 ID \u641c\u7d22\u6240\u6709\u539f\u7248\u7269\u54c1",
                "\u00a77\u70b9\u51fb\u6253\u5f00\u641c\u7d22\u8f93\u5165"
            ));
        }
        guiState.put(player.getUniqueId(), state);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), Math.max(1, sourcePage));
        player.openInventory(inv);
    }

    public static void openListingMenu(StockExchangePlugin plugin, Player player, int sourcePage) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Map<String, Integer> stale = listingPending.remove(uuid);
        if (stale != null && !stale.isEmpty()) {
            ExchangeGUI.returnListingItems(player, stale);
        }
        Inventory inv = Bukkit.createInventory(null, 27, "\u00a76\u4e0a\u67b6\u5546\u54c1");
        inv.setItem(4, ExchangeGUI.createItem(
            Material.EMERALD,
            "\u00a7a\u4e0a\u67b6\u8bf4\u660e",
            "\u00a77\u5c06\u8981\u4e0a\u67b6\u7684\u7269\u54c1\u653e\u5165\u4e0b\u65b9\u8f93\u5165\u69fd",
            "\u00a77\u70b9\u51fb\u786e\u8ba4\u540e\u8f93\u5165\u5355\u4ef7\uff0c\u6309\u8be5\u4ef7\u683c\u5168\u90e8\u4e0a\u67b6",
            "\u00a77\u53d6\u6d88\u6216\u9000\u51fa\u65f6\u653e\u5165\u7684\u7269\u54c1\u4f1a\u5168\u90e8\u8fd8\u8fd8",
            "\u00a77\u5df2\u653e\u5165\u7684\u7269\u54c1\u4f1a\u5c55\u793a\u5728\u4e0b\u65b9\u683c\u5b50\u4e2d"
        ));
        inv.setItem(ADD_ITEM_INPUT_SLOT, null);
        inv.setItem(LISTING_CONFIRM_SLOT, ExchangeGUI.createItemWithModelData(
            Material.LIME_WOOL,
            "\u00a7a\u786e\u8ba4\u4e0a\u67b6",
            2400014,
            "\u00a77\u8f93\u5165\u5355\u4ef7\u540e\u6309\u8be5\u4ef7\u683c\u5168\u90e8\u4e0a\u67b6"
        ));
        inv.setItem(LISTING_CANCEL_SLOT, ExchangeGUI.createItemWithModelData(
            Material.RED_WOOL,
            "\u00a7c\u53d6\u6d88\u4e0a\u67b6",
            2400015,
            "\u00a77\u53d6\u6d88\u5e76\u8fd8\u8fd8\u6240\u6709\u653e\u5165\u7684\u7269\u54c1"
        ));
        guiState.put(uuid, LISTING);
        guiItemId.remove(uuid);
        guiPage.put(uuid, Math.max(1, sourcePage));
        listingPending.put(uuid, new LinkedHashMap<String, Integer>());
        player.openInventory(inv);
    }

    public static Map<String, Integer> getListingPending(Player player) {
        return listingPending.get(player.getUniqueId());
    }

    public static void enterListingPriceInput(Player player) {
        UUID uuid = player.getUniqueId();
        guiNavigating.put(uuid, true);
        guiState.put(uuid, LISTING_PRICE);
    }

    public static void cancelListing(StockExchangePlugin plugin, Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Integer> pending = listingPending.remove(uuid);
        if (pending != null && !pending.isEmpty()) {
            ExchangeGUI.returnListingItems(player, pending);
        }
        guiState.remove(uuid);
        guiItemId.remove(uuid);
        guiDetailPage.remove(uuid);
    }

    public static void completeListing(StockExchangePlugin plugin, Player player, BigDecimal price) {
        UUID uuid = player.getUniqueId();
        Map<String, Integer> pending = listingPending.remove(uuid);
        guiState.remove(uuid);
        guiItemId.remove(uuid);
        guiDetailPage.remove(uuid);
        if (pending == null || pending.isEmpty()) {
            ExchangeGUI.openItemList(plugin, player);
            return;
        }
        Map<String, Integer> remaining = new HashMap<String, Integer>(pending);
        int listed = 0;
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            int amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            ItemStack actual = ItemSerializer.itemFromBase64(entry.getKey());
            if (actual == null) {
                continue;
            }
            ExchangeItem categoryItem = plugin.getItemManager().resolveSpecialItem(actual);
            if (categoryItem == null) {
                categoryItem = plugin.getItemManager().registerItem(actual, player);
            }
            if (categoryItem == null) {
                continue;
            }
            int left = amount;
            while (left > 0) {
                int chunk = Math.min(left, plugin.getMaxOrderQuantity());
                String result = plugin.getOrderManager().placeSellOrderFromReserved(
                    player, categoryItem, entry.getKey(), price, chunk
                );
                if (result.startsWith("\u00a7c")) {
                    player.sendMessage(result);
                    break;
                }
                listed += chunk;
                left -= chunk;
                remaining.put(entry.getKey(), left > 0 ? left : 0);
            }
        }
        remaining.values().removeIf(value -> value == null || value <= 0);
        if (!remaining.isEmpty()) {
            ExchangeGUI.returnListingItems(player, remaining);
        }
        if (listed > 0) {
            plugin.getTradeNoticeBuffer().manual(player, "\u00a7a\u4e0a\u67b6\u5b8c\u6210\uff1a\u5171 " + listed
                + " \u4e2a\u7269\u54c1\u4ee5\u5355\u4ef7 " + price + " \u4e0a\u67b6\u3002");
        } else {
            plugin.getTradeNoticeBuffer().manual(player, "\u00a7c\u6ca1\u6709\u4efb\u4f55\u7269\u54c1\u4e0a\u67b6\u6210\u529f\u3002");
        }
        ExchangeGUI.openItemList(plugin, player);
    }

    private static void addListingItem(Player player, ItemStack source, InventoryClickEvent event) {
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        if (plugin.denyGrowthAccess(player) || !LISTING.equals(guiState.get(player.getUniqueId()))) {
            return;
        }
        if (source == null || source.getType() == Material.AIR || MarketGuiItem.isMarked(source)) {
            return;
        }
        ItemStack single = source.clone();
        single.setAmount(1);
        String base64 = ItemSerializer.itemToBase64(single);
        int amount = source.getAmount();
        if (base64 == null || amount <= 0) {
            player.sendMessage("\u00a7c\u7269\u54c1\u5e8f\u5217\u5316\u5931\u8d25\u3002");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!plugin.getStorageManager().addToPlayerItemWarehouse(uuid.toString(), base64, amount)) {
            player.sendMessage("\u00a7c\u6682\u5b58\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
            return;
        }
        Map<String, Integer> pending = listingPending.computeIfAbsent(uuid, key -> new LinkedHashMap<String, Integer>());
        pending.merge(base64, amount, Integer::sum);
        if (event != null && event.getRawSlot() == ADD_ITEM_INPUT_SLOT
            && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
            player.setItemOnCursor(null);
        } else if (event != null && event.getClickedInventory() != null) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        }
        ExchangeGUI.renderListingInputSlot(player);
        int total = 0;
        for (Integer count : pending.values()) {
            total += count;
        }
        player.sendMessage("\u00a7a\u5df2\u653e\u5165 " + amount + " \u4e2a\u7269\u54c1\uff08\u5171\u6682\u5b58 " + total + " \u4e2a\uff09\u3002");
    }

    private static void renderListingInputSlot(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        UUID uuid = player.getUniqueId();
        if (inv == null || inv.getSize() != 27 || !LISTING.equals(guiState.get(uuid))) {
            return;
        }
        for (int slot : LISTING_DISPLAY_SLOTS) {
            inv.setItem(slot, null);
        }
        Map<String, Integer> pending = listingPending.get(uuid);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        int total = 0;
        for (Integer count : pending.values()) {
            total += count;
        }
        boolean hasExtras = pending.size() > LISTING_DISPLAY_SLOTS.length;
        int index = 0;
        ArrayList<Map.Entry<String, Integer>> extras = new ArrayList<Map.Entry<String, Integer>>();
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            int displayIndex = index;
            if (hasExtras && displayIndex >= 4) {
                // 槽 13 是输入槽，超量时改作汇总展示，图标跳过该槽位
                displayIndex += 1;
            }
            if (displayIndex < LISTING_DISPLAY_SLOTS.length) {
                ItemStack item = ItemSerializer.itemFromBase64(entry.getKey());
                if (item != null) {
                    inv.setItem(LISTING_DISPLAY_SLOTS[displayIndex], ExchangeGUI.createListingDisplayItem(item, entry.getValue()));
                }
            } else {
                extras.add(entry);
            }
            ++index;
        }
        if (!extras.isEmpty()) {
            inv.setItem(ADD_ITEM_INPUT_SLOT, ExchangeGUI.createListingSummaryItem(pending, total, extras));
        }
    }

    private static ItemStack createListingDisplayItem(ItemStack base, int count) {
        ItemStack item = base.clone();
        item.setAmount(Math.max(1, Math.min(count, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        String name = ItemDisplayNames.resolve(base);
        if (name == null || name.isEmpty()) {
            name = base.getType().name();
        }
        meta.setDisplayName("\u00a7a" + name + " \u00a77x " + count);
        meta.setLore(Arrays.asList(
            "\u00a77\u786e\u8ba4\u540e\u8f93\u5165\u5355\u4ef7\u4e0a\u67b6",
            "\u00a77\u53d6\u6d88\u6216\u9000\u51fa\u65f6\u5168\u90e8\u8fd8\u8fd8"
        ));
        item.setItemMeta(meta);
        MarketGuiItem.mark(item);
        return item;
    }

    private static ItemStack createListingSummaryItem(
        Map<String, Integer> pending,
        int total,
        List<Map.Entry<String, Integer>> extras
    ) {
        ItemStack summary = new ItemStack(Material.PAPER);
        ItemMeta meta = summary.getItemMeta();
        meta.setDisplayName("\u00a7a\u5df2\u653e\u5165 " + total + " \u4e2a\u7269\u54c1\uff08\u5171 " + pending.size() + " \u79cd\uff09");
        ArrayList<String> lore = new ArrayList<String>();
        int line = 0;
        for (Map.Entry<String, Integer> entry : extras) {
            if (line >= 8) {
                lore.add("\u00a77...");
                break;
            }
            ItemStack stack = ItemSerializer.itemFromBase64(entry.getKey());
            String name = stack == null ? "\u672a\u77e5\u7269\u54c1" : ItemDisplayNames.resolve(stack);
            lore.add("\u00a77" + name + " x" + entry.getValue());
            ++line;
        }
        lore.add("\u00a77\u786e\u8ba4\u540e\u8f93\u5165\u5355\u4ef7\u4e0a\u67b6");
        lore.add("\u00a77\u53d6\u6d88\u6216\u9000\u51fa\u65f6\u5168\u90e8\u8fd8\u8fd8");
        meta.setLore(lore);
        summary.setItemMeta(meta);
        MarketGuiItem.mark(summary);
        return summary;
    }

    private static void returnListingItems(Player player, Map<String, Integer> pending) {
        if (player == null || pending == null || pending.isEmpty()) {
            return;
        }
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        String playerUuid = player.getUniqueId().toString();
        int returned = 0;
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            int quantity = entry.getValue();
            if (quantity <= 0) {
                continue;
            }
            if (!plugin.getStorageManager().takeFromPlayerItemWarehouse(playerUuid, entry.getKey(), quantity)) {
                continue;
            }
            ItemStack item = ItemSerializer.itemFromBase64(entry.getKey());
            if (item == null) {
                plugin.getStorageManager().addToPlayerItemWarehouse(playerUuid, entry.getKey(), quantity);
                continue;
            }
            int added = InventoryDelivery.addUpTo(player, item, quantity);
            returned += added;
            int remaining = quantity - added;
            if (remaining > 0) {
                plugin.getStorageManager().addToPlayerItemWarehouse(playerUuid, entry.getKey(), remaining);
            }
        }
        if (returned > 0 && player.isOnline()) {
            player.sendMessage("\u00a7a\u5df2\u8fd8\u8fd8 " + returned + " \u4e2a\u7269\u54c1\u3002");
        }
    }

    public static void openBedrockCurrencyExchangeForm(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        BigDecimal tax = TaxCalculator.tax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        BigDecimal received = TaxCalculator.afterTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        BigDecimal totalCost = TaxCalculator.withTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        SimpleForm.Builder builder = SimpleForm.builder()
            .title("\u8d27\u5e01\u5151\u6362")
            .content("\u5f53\u524d\u7a0e\u7387: " + plugin.getTaxRatePercent().toPlainString() + "%\n\u6bcf\u6b21\u5151\u6362\u7a0e\u989d: " + tax.toPlainString() + " " + plugin.getCurrencyName())
            .button("1 \u94bb\u77f3 -> " + received.toPlainString() + " " + plugin.getCurrencyName())
            .button(totalCost.toPlainString() + " " + plugin.getCurrencyName() + " -> 1 \u94bb\u77f3")
            .button("\u8fd4\u56de")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(plugin, () -> {
                int index = response.clickedButtonId();
                if (index == 0) {
                    player.sendMessage(plugin.exchangeDiamondForMoney(player));
                    openBedrockCurrencyExchangeForm(plugin, player);
                } else if (index == 1) {
                    player.sendMessage(plugin.exchangeMoneyForDiamond(player));
                    openBedrockCurrencyExchangeForm(plugin, player);
                } else {
                    openBedrockItemListForm(plugin, player);
                }
            }));
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    public static void openBedrockAddItemForm(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        List<ItemStack> hotbarItems = new ArrayList<ItemStack>();
        SimpleForm.Builder builder = SimpleForm.builder()
            .title("\u6dfb\u52a0\u5546\u54c1")
            .content("\u8bf7\u4ece\u5feb\u6377\u680f\u9009\u62e9\u8981\u4e0a\u5e02\u7684\u7269\u54c1");
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            hotbarItems.add(stack.clone());
            builder.button(ItemSerializer.getItemDisplayName(stack) + " x" + stack.getAmount());
        }
        builder.button("\u8fd4\u56de")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(plugin, () -> {
                int index = response.clickedButtonId();
                if (index < 0) {
                    return;
                }
                 if (index >= hotbarItems.size()) {
                     openBedrockItemListForm(plugin, player);
                     return;
                }
                ItemStack selected = hotbarItems.get(index);
                ItemManager.RegisterResult result = plugin.getItemManager().registerCatalogItem(player, selected);
                player.sendMessage(result.getMessage());
                if (result.isSuccess() && result.getItem() != null) {
                    ExchangeGUI.openBedrockItemListForm(plugin, player);
                } else {
                    openBedrockItemListForm(plugin, player);
                }
             }));
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    public static void openBedrockMainForm(StockExchangePlugin plugin, Player player) {
        ExchangeGUI.openItemList(plugin, player);
    }

    public static void openBedrockItemListForm(StockExchangePlugin plugin, Player player) {
        ExchangeGUI.openItemList(plugin, player);
    }

    public static void openItemDetail(StockExchangePlugin plugin, Player player, ExchangeItem item) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.guiDetailPage.put(player.getUniqueId(), 1);
        ExchangeGUI.openItemDetail(plugin, player, item, 1);
    }

    private static void openItemDetail(
        StockExchangePlugin plugin,
        Player player,
        ExchangeItem item,
        int requestedPage
    ) {
        boolean bulk = bulkBuyMode.getOrDefault(player.getUniqueId(), false);
        boolean isBuy = ExchangeGUI.isBuyPage(plugin, player);
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        if (baseItem == null) {
            player.sendMessage("\u00a7c\u7269\u54c1\u6570\u636e\u635f\u574f\uff0c\u65e0\u6cd5\u6253\u5f00\u8be6\u60c5\u3002");
            return;
        }
        String itemDisplayName = ExchangeGUI.resolveDisplayName(item, baseItem);
        String modeSuffix = isBuy ? " \u00a77| \u00a7c\u6c42\u8d2d\u6a21\u5f0f" : " \u00a77| \u00a7a\u51fa\u552e\u6a21\u5f0f";
        Inventory inv = Bukkit.createInventory(null, 54, "\u00a76\u54c1\u79cd\u8be6\u60c5: " + itemDisplayName + modeSuffix);
        ItemStack glass = ExchangeGUI.createItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "\u00a77", new String[]{null});
        List<MarketListingLayout.Slot> listingSlots;
        int totalPages;
        int currentPage;
        int slot = 0;
        if (isBuy) {
            List<Order> buyOrders = new ArrayList<Order>(plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.BUY));
            buyOrders.sort(Comparator.comparing(Order::getPrice).reversed().thenComparing(Order::getCreatedAt));
            listingSlots = MarketListingLayout.expand(buyOrders, baseItem.getMaxStackSize());
            totalPages = MarketListingLayout.pageCount(listingSlots, ITEM_DETAIL_PAGE_SIZE);
            currentPage = Math.max(1, Math.min(requestedPage, totalPages));
            int start = (currentPage - 1) * ITEM_DETAIL_PAGE_SIZE;
            int end = Math.min(start + ITEM_DETAIL_PAGE_SIZE, listingSlots.size());
            for (int index = start; index < end; ++index) {
                MarketListingLayout.Slot listingSlot = listingSlots.get(index);
                Order buyOrder = listingSlot.order();
                int displayedQuantity = listingSlot.amount();
                ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, itemDisplayName);
                ExchangeGUI.setDisplayAmount(displayItem, displayedQuantity);
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<String>();
                    lore.add("\u00a77\u7269\u54c1: \u00a7f" + itemDisplayName);
                    lore.add("\u00a77\u4e70\u5bb6: \u00a7f" + buyOrder.getPlayerName());
                    lore.add("\u00a77\u6c42\u8d2d\u4ef7: " + ExchangeGUI.formatHighlightedPrice(buyOrder.getPrice()));
                    lore.add("\u00a77\u8fd9\u683c\u6570\u91cf: \u00a7f" + displayedQuantity);
                    lore.add("\u00a77\u8be5\u6c42\u8d2d\u5355\u5269\u4f59: \u00a7f" + buyOrder.getRemainingQty());
                    lore.add("");
                    boolean ownOrder = buyOrder.getPlayerUuid().equals(player.getUniqueId().toString());
                    if (ownOrder) {
                        meta.setEnchantmentGlintOverride(true);
                        lore.add("\u00a7e\u5de6\u952e: \u51cf\u5c11 1 \u4e2a\u6c42\u8d2d");
                        lore.add("\u00a7eShift+\u5de6\u952e: \u53d6\u6d88\u672c\u683c\u5168\u90e8\u6c42\u8d2d");
                    } else {
                        lore.add("\u00a7e\u70b9\u51fb\u4f9b\u8d27 1 \u4e2a");
                        lore.add("\u00a7eShift+\u5de6\u952e: \u6309\u672c\u683c\u6570\u91cf\u4f9b\u8d27\uff08\u80cc\u5305\u4e0d\u8db3\u5219\u6709\u591a\u5c11\u4f9b\u591a\u5c11\uff09");
                    }
                    lore.add("\u00a70ORDER:" + buyOrder.getId());
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(slot++, displayItem);
            }
            for (int s = slot; s <= 44; ++s) {
                inv.setItem(s, glass);
            }
            for (int s = 46; s <= 50; ++s) {
                inv.setItem(s, glass);
            }
            SupplyPlanner.Plan supplyPlan = plugin.getOrderManager().getSupplyPlan(player, item);
            BigDecimal supplyTax = supplyPlan.taxAmount(plugin.getTaxRatePercent());
            BigDecimal expectedReceived = supplyPlan.grossAmount().subtract(supplyTax);
            inv.setItem(45, ExchangeGUI.createItem(
                Material.GOLD_INGOT,
                "\u00a7e\u4e00\u952e\u4f9b\u8d27",
                "\u00a77\u80cc\u5305\u540c\u7c7b\u7269\u54c1: \u00a7f" + supplyPlan.availableQuantity() + " \u4e2a",
                "\u00a77\u53ef\u5339\u914d\u4f9b\u8d27: \u00a7f" + supplyPlan.matchedQuantity() + " \u4e2a",
                "\u00a77\u4ec5\u5339\u914d\u5176\u4ed6\u73a9\u5bb6\u7684\u6c42\u8d2d\u5355",
                "\u00a77\u5c06\u6309\u6c42\u8d2d\u4ef7\u4ece\u9ad8\u5230\u4f4e\u81ea\u52a8\u5206\u914d",
                "\u00a77\u9884\u8ba1\u6210\u4ea4\u989d: \u00a7f" + supplyPlan.grossAmount().toPlainString(),
                "\u00a77\u9884\u8ba1\u5230\u8d26: \u00a7f" + expectedReceived.toPlainString()
                    + " \uff08\u6263\u7a0e " + supplyTax.toPlainString() + "\uff09",
                "\u00a7e\u70b9\u51fb\u5c06\u6240\u6709\u5339\u914d\u7269\u54c1\u7528\u4e8e\u4f9b\u8d27"
            ));
            inv.setItem(49, ExchangeGUI.createItem(Material.REDSTONE, "\u00a7b\u6c42\u8d2d\u8be5\u7269\u54c1", "\u00a77\u8f93\u5165\u4ef7\u683c\u548c\u6570\u91cf\u53d1\u8d77\u6c42\u8d2d\u5355"));
            inv.setItem(51, glass);
            inv.setItem(LARGE_NEXT_SLOT, glass);
            inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u5217\u8868"));
        } else {
            List<Order> sellOrders = new ArrayList<Order>(plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.SELL));
            sellOrders.sort(Comparator.comparing(Order::getPrice).thenComparing(Order::getCreatedAt));
            listingSlots = MarketListingLayout.expand(sellOrders, baseItem.getMaxStackSize());
            totalPages = MarketListingLayout.pageCount(listingSlots, ITEM_DETAIL_PAGE_SIZE);
            currentPage = Math.max(1, Math.min(requestedPage, totalPages));
            int start = (currentPage - 1) * ITEM_DETAIL_PAGE_SIZE;
            int end = Math.min(start + ITEM_DETAIL_PAGE_SIZE, listingSlots.size());
            for (int index = start; index < end; ++index) {
                MarketListingLayout.Slot listingSlot = listingSlots.get(index);
                Order sellOrder = listingSlot.order();
                int displayedQuantity = listingSlot.amount();
                EscrowEntry sellEscrow = plugin.getStorageManager().getEscrow(
                    sellOrder.getId(), EscrowEntry.AssetType.ITEM);
                ItemStack orderBase = sellEscrow == null
                    ? null : ItemSerializer.itemFromBase64(sellEscrow.getItemBase64());
                String orderItemName = itemDisplayName;
                if (orderBase == null) {
                    orderBase = baseItem;
                } else {
                    orderItemName = ItemDisplayNames.resolve(orderBase);
                }
                ItemStack displayItem = ExchangeGUI.createMarketVoucher(orderBase, orderItemName);
                ExchangeGUI.setDisplayAmount(displayItem, displayedQuantity);
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<String>();
                    lore.add("\u00a77\u7269\u54c1: \u00a7f" + orderItemName);
                    lore.add("\u00a77\u5356\u5bb6: \u00a7f" + sellOrder.getPlayerName());
                    lore.add("\u00a77\u5355\u4ef7: " + ExchangeGUI.formatHighlightedPrice(sellOrder.getPrice()));
                    lore.add("\u00a77\u8fd9\u683c\u6570\u91cf: \u00a7f" + displayedQuantity);
                    lore.add("\u00a77\u8be5\u5356\u5355\u5269\u4f59: \u00a7f" + sellOrder.getRemainingQty());
                    lore.add("");
                    boolean ownOrder = sellOrder.getPlayerUuid().equals(player.getUniqueId().toString());
                    if (ownOrder) {
                        meta.setEnchantmentGlintOverride(true);
                        lore.add("\u00a7e\u5de6\u952e: \u53d6\u56de 1 \u4e2a");
                        lore.add("\u00a7eShift+\u5de6\u952e: \u53d6\u56de\u672c\u683c\u5168\u90e8\u5546\u54c1");
                    } else {
                        if (bulk) {
                            lore.add("\u00a7e\u70b9\u51fb\u8d2d\u4e70 " + displayedQuantity + " \u4e2a");
                        } else {
                            lore.add("\u00a7e\u70b9\u51fb\u8d2d\u4e70 1 \u4e2a");
                        }
                        lore.add("\u00a7eShift+\u5de6\u952e: \u5feb\u901f\u8d2d\u4e70\u672c\u683c " + displayedQuantity + " \u4e2a");
                    }
                    lore.add("\u00a70ORDER:" + sellOrder.getId());
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(slot++, displayItem);
            }
            for (int s = slot; s <= 44; ++s) {
                inv.setItem(s, glass);
            }
            for (int s = 46; s <= 50; ++s) {
                inv.setItem(s, glass);
            }
            String modeText = bulk ? "\u00a7c\u6279\u91cf\u8d2d\u4e70" : "\u00a7a\u5355\u4e2a\u8d2d\u4e70";
            inv.setItem(45, ExchangeGUI.createItem(Material.HOPPER, "\u00a7e\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f \u00a77| " + modeText, "\u00a77\u70b9\u51fb\u5207\u6362"));
            if (plugin.getItemManager().getSpecialCategory(item) == null) {
                inv.setItem(51, ExchangeGUI.createItem(Material.GLOWSTONE_DUST, "\u00a76\u5feb\u901f\u4e0a\u67b6\u8be5\u7269\u54c1", "\u00a77\u4e00\u952e\u6309\u6700\u4f4e\u4ef7\u4e0a\u67b6\u80cc\u5305\u4e2d\u6240\u6709\u540c\u7c7b\u578b\u5546\u54c1"));
            } else {
                inv.setItem(51, glass);
            }
            inv.setItem(LARGE_NEXT_SLOT, glass);
            inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u5217\u8868"));
        }
        String pageLore = "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875";
        inv.setItem(ITEM_DETAIL_PREV_SLOT, ExchangeGUI.navigationItem(
            PAGE_PREV,
            currentPage > 1,
            pageLore
        ));
        inv.setItem(ITEM_DETAIL_NEXT_SLOT, ExchangeGUI.navigationItem(
            PAGE_NEXT,
            currentPage < totalPages,
            pageLore
        ));
        guiState.put(player.getUniqueId(), ITEM_DETAIL);
        guiItemId.put(player.getUniqueId(), item.getId());
        guiDetailPage.put(player.getUniqueId(), currentPage);
        player.openInventory(inv);
    }

    public static void openOrderBook(StockExchangePlugin plugin, Player player, ExchangeItem item) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        Inventory inv = Bukkit.createInventory(null, (int)54, (String)("\u00a76\u76d8\u53e3: " + item.getDisplayName()));
        List<Order> buyOrders = plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.BUY);
        List<Order> sellOrders = plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.SELL);
        ItemStack buyTitle = ExchangeGUI.createItem(new ItemStack(Material.RED_STAINED_GLASS_PANE), "\u00a7c=== \u4e70\u5355 ===", new String[]{null});
        inv.setItem(0, buyTitle);
        int slot = 1;
        for (int i = 0; i < Math.min(buyOrders.size(), 21); ++i) {
            Order order = buyOrders.get(i);
            ItemStack orderItem = ExchangeGUI.createItem(Material.RED_DYE, "\u00a7c\u4e70\u5355 #" + order.getId(), "\u00a77\u73a9\u5bb6: " + safeText(order.getPlayerName(), "\u672a\u77e5\u73a9\u5bb6") + " (" + shortUuid(order.getPlayerUuid()) + ")", "\u00a77\u4ef7\u683c: \u00a7f" + ExchangeGUI.formatPrice(order.getPrice()), "\u00a77\u6570\u91cf: \u00a7f" + order.getRemainingQty() + "/" + order.getQuantity(), "\u00a77\u5df2\u6210\u4ea4: \u00a7f" + order.getFilledQty(), "\u00a77\u72b6\u6001: " + ExchangeGUI.getStatusString(order.getStatus()));
            inv.setItem(slot++, orderItem);
        }
        ItemStack separator = ExchangeGUI.createItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "\u00a77--- \u76d8\u53e3 ---", new String[]{null});
        inv.setItem(22, separator);
        ItemStack sellTitle = ExchangeGUI.createItem(new ItemStack(Material.GREEN_STAINED_GLASS_PANE), "\u00a7a=== \u5356\u5355 ===", new String[]{null});
        inv.setItem(23, sellTitle);
        slot = 24;
        for (int i = 0; i < Math.min(sellOrders.size(), 21); ++i) {
            Order order = sellOrders.get(i);
            ItemStack orderItem = ExchangeGUI.createItem(Material.GREEN_DYE, "\u00a7a\u5356\u5355 #" + order.getId(), "\u00a77\u73a9\u5bb6: " + safeText(order.getPlayerName(), "\u672a\u77e5\u73a9\u5bb6") + " (" + shortUuid(order.getPlayerUuid()) + ")", "\u00a77\u4ef7\u683c: \u00a7f" + ExchangeGUI.formatPrice(order.getPrice()), "\u00a77\u6570\u91cf: \u00a7f" + order.getRemainingQty() + "/" + order.getQuantity(), "\u00a77\u5df2\u6210\u4ea4: \u00a7f" + order.getFilledQty(), "\u00a77\u72b6\u6001: " + ExchangeGUI.getStatusString(order.getStatus()));
            inv.setItem(slot++, orderItem);
        }
        ItemStack backItem = ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u8be6\u60c5");
        inv.setItem(LARGE_BACK_SLOT, backItem);
        guiState.put(player.getUniqueId(), ORDER_BOOK);
        guiItemId.put(player.getUniqueId(), item.getId());
        player.openInventory(inv);
    }

    public static void openMyOrders(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openMyHistory(plugin, player, 1);
    }

    private static void openMyOrders(StockExchangePlugin plugin, Player player, int page) {
        ExchangeGUI.openMyHistory(plugin, player, page);
    }

    public static void openMyTrades(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openMyHistory(plugin, player, 1);
    }

    private static void openMyTrades(StockExchangePlugin plugin, Player player, int page) {
        ExchangeGUI.openMyHistory(plugin, player, page);
    }

    public static void openMyHistory(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openMyHistory(plugin, player, 1);
    }

    private static void openMyHistory(StockExchangePlugin plugin, Player player, int page) {
        List<Order> orders = plugin.getOrderManager().getPlayerOrders(player.getUniqueId().toString());
        List<Trade> trades = plugin.getStorageManager().getTradesByPlayer(player.getUniqueId().toString(), 200, 0);
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, (int)size, (String)"\u00a76\u6211\u7684\u4ea4\u6613\u8bb0\u5f55");
        int pageSize = size - 9;
        int totalEntries = orders.size() + trades.size();
        int totalPages = Math.max(1, (totalEntries + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, totalEntries);
        int index = 0;
        int slot = 0;
        for (Order order : orders) {
            if (index >= end) {
                break;
            }
            if (index >= start) {
                ExchangeItem item = plugin.getItemManager().getItem(order.getItemId());
                String itemName = ExchangeGUI.resolveHistoryItemName(item, order.getItemId());
                boolean isSell = order.getOrderType() == Order.OrderType.SELL;
                String typeStr = order.getOrderType() == Order.OrderType.BUY ? "\u00a7e\u4e70\u5165\u6302\u5355" : "\u00a7a\u5356\u51fa\u6302\u5355";
                ItemStack orderItem = ExchangeGUI.createHistoryIcon(
                    item,
                    order.getQuantity(),
                    isSell,
                    typeStr + " #" + order.getId() + " \u00a77| \u00a7f" + itemName,
                    "\u00a77\u54c1\u79cd: \u00a7f" + itemName,
                    "\u00a77\u5355\u4ef7: \u00a7f" + ExchangeGUI.formatPrice(order.getPrice()),
                    "\u00a77\u6570\u91cf: \u00a7f" + order.getFilledQty() + "/" + order.getQuantity(),
                    "\u00a77\u5269\u4f59: \u00a7f" + order.getRemainingQty(),
                    "\u00a77\u72b6\u6001: " + ExchangeGUI.getStatusString(order.getStatus()),
                    "\u00a7e\u70b9\u51fb\u53d6\u6d88\u8be5\u6302\u5355"
                );
                inv.setItem(slot++, orderItem);
            }
            ++index;
        }
        for (Trade trade : trades) {
            if (index >= end) {
                break;
            }
            if (index < start) {
                ++index;
                continue;
            }
            ExchangeItem item = plugin.getItemManager().getItem(trade.getItemId());
            String itemName = ExchangeGUI.resolveHistoryItemName(item, trade.getItemId());
            boolean isBuy = trade.getBuyerUuid().equals(player.getUniqueId().toString());
            String role = isBuy ? "\u00a7e\u4e70\u5165\u8bb0\u5f55" : "\u00a7a\u5356\u51fa\u8bb0\u5f55";
            BigDecimal fee = isBuy ? trade.getBuyerFee() : trade.getSellerFee();
            ItemStack tradeItem = ExchangeGUI.createHistoryIcon(
                item,
                trade.getQuantity(),
                !isBuy,
                role + " #" + trade.getId() + " \u00a77| \u00a7f" + itemName,
                "\u00a77\u54c1\u79cd: \u00a7f" + itemName,
                "\u00a77\u5355\u4ef7: \u00a7f" + ExchangeGUI.formatPrice(trade.getPrice()),
                "\u00a77\u6570\u91cf: \u00a7f" + trade.getQuantity(),
                "\u00a77\u603b\u989d: \u00a7f" + ExchangeGUI.formatPrice(trade.getTotalAmount()),
                "\u00a77\u4ea4\u6613\u7a0e: \u00a7f" + ExchangeGUI.formatPrice(fee),
                "\u00a77\u6210\u4ea4\u65f6\u95f4: " + trade.getTradedAt().toString()
            );
            inv.setItem(slot++, tradeItem);
            ++index;
        }
        int navBase = size - 9;
        inv.setItem(LARGE_PREV_SLOT, ExchangeGUI.navigationItem(
            PAGE_PREV,
            currentPage > 1,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        ItemStack backItem = ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u4e3b\u83dc\u5355");
        inv.setItem(LARGE_BACK_SLOT, backItem);
        inv.setItem(LARGE_NEXT_SLOT, ExchangeGUI.navigationItem(
            PAGE_NEXT,
            currentPage < totalPages,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        guiState.put(player.getUniqueId(), MY_HISTORY);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), currentPage);
        player.openInventory(inv);
    }

    public static void openWarehouse(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openWarehouse(plugin, player, 1);
    }

    public static void openAnnouncements(StockExchangePlugin plugin, Player player) {
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        ExchangeGUI.openAnnouncements(plugin, player, 1);
    }

    private static void openAnnouncements(StockExchangePlugin plugin, Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, (int)54, (String)"\u00a76\u4ea4\u6613\u6240\u516c\u544a\u680f");
        List<String> announcements = plugin.getAnnouncements();
        int pageSize = 45;
        int totalPages = Math.max(1, (announcements.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, announcements.size());
        int slot = 0;
        for (int i = start; i < end; ++i) {
            String content = announcements.get(i);
            ArrayList<String> signLore = new ArrayList<String>();
            for (String line : content.split("\\n")) {
                signLore.add("\u00a7f" + line);
            }
            signLore.add("\u00a77------------------");
            signLore.add("\u00a77\u7531\u7ba1\u7406\u5458\u521b\u5efa/\u7f16\u8f91");
            ItemStack sign = ExchangeGUI.createItem(Material.OAK_SIGN, "\u00a7e\u516c\u544a #" + (i + 1), signLore.toArray(new String[0]));
            inv.setItem(slot++, sign);
        }
        if (announcements.isEmpty()) {
            inv.setItem(22, ExchangeGUI.createItem(Material.PAPER, "\u00a77\u6682\u65e0\u516c\u544a", "\u00a77\u7ba1\u7406\u5458\u53ef\u4f7f\u7528 /se announce add \u65b0\u589e"));
        }
        inv.setItem(LARGE_PREV_SLOT, ExchangeGUI.navigationItem(
            PAGE_PREV,
            currentPage > 1,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u4e3b\u83dc\u5355"));
        inv.setItem(LARGE_NEXT_SLOT, ExchangeGUI.navigationItem(
            PAGE_NEXT,
            currentPage < totalPages,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        guiState.put(player.getUniqueId(), ANNOUNCEMENTS);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), currentPage);
        player.openInventory(inv);
    }

    private static void openWarehouse(StockExchangePlugin plugin, Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, (int)54, (String)"\u00a76\u4ed3\u5e93");
        BigDecimal money = plugin.getStorageManager().getMoneyWarehouseBalance(player.getUniqueId().toString());
        ItemStack moneyItem = ExchangeGUI.createItem(Material.GOLD_INGOT, "\u00a7e\u4ed3\u5e93\u661f\u5149\u70b9", "\u00a77\u5f53\u524d\u53ef\u63d0\u53d6: \u00a7f" + String.format("%.2f", money));
        inv.setItem(4, moneyItem);
        Map<String, Integer> snapshot = plugin.getStorageManager()
            .getPlayerItemWarehouse(player.getUniqueId().toString());
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>();
        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            if (entry.getValue() <= 0) continue;
            entries.add(entry);
        }
        int pageSize = 36;
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, entries.size());
        int slot = 9;
        Map<Integer, String> slotEntries = new HashMap<Integer, String>();
        for (int idx = start; idx < end; ++idx) {
            Map.Entry entry = (Map.Entry)entries.get(idx);
            ItemStack realItem = ItemSerializer.itemFromBase64((String)entry.getKey());
            if (realItem == null) continue;
            ItemStack item = ExchangeGUI.createMarketVoucher(realItem, ItemDisplayNames.resolve(realItem));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                ArrayList<String> lore = new ArrayList<String>();
                lore.add("\u00a77\u4ed3\u5e93\u6570\u91cf: \u00a7f" + entry.getValue());
                lore.add("\u00a7e\u70b9\u51fb\u63d0\u53d6");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
            slotEntries.put(slot, (String)entry.getKey());
            ++slot;
        }
        inv.setItem(LARGE_PREV_SLOT, ExchangeGUI.navigationItem(
            PAGE_PREV,
            currentPage > 1,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        ItemStack withdrawItem = ExchangeGUI.createItem(Material.CHEST, "\u00a7a\u4e00\u952e\u63d0\u53d6", "\u00a77\u63d0\u53d6\u4ed3\u5e93\u4e2d\u7684\u7269\u54c1\u548c\u661f\u5149\u70b9");
        inv.setItem(49, withdrawItem);
        ItemStack backItem = ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u4e3b\u83dc\u5355");
        inv.setItem(LARGE_BACK_SLOT, backItem);
        inv.setItem(LARGE_NEXT_SLOT, ExchangeGUI.navigationItem(
            PAGE_NEXT,
            currentPage < totalPages,
            "\u00a77\u7b2c " + currentPage + "/" + totalPages + " \u9875"
        ));
        guiState.put(player.getUniqueId(), WAREHOUSE);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), currentPage);
        guiWarehouseEntries.put(player.getUniqueId(), slotEntries);
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player clicker = (Player)event.getWhoClicked();
        UUID uuid = clicker.getUniqueId();
        String state = guiState.get(uuid);
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        if (plugin.denyGrowthAccess(clicker)) {
            clicker.closeInventory();
            return;
        }
        if (ADD_ITEM.equals(state) || ADD_BUY_ITEM.equals(state) || LISTING.equals(state)) {
            Inventory topInventory = event.getView().getTopInventory();
            int rawSlot = event.getRawSlot();
            if (rawSlot == ADD_ITEM_INPUT_SLOT) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    if (LISTING.equals(state)) {
                        ExchangeGUI.addListingItem(clicker, cursor, event);
                    } else {
                        ExchangeGUI.handleSelectedItem(clicker, cursor, ADD_BUY_ITEM.equals(state));
                    }
                    return;
                }
                return;
            }
            ItemStack clickedStack = event.getCurrentItem();
            if (rawSlot >= topInventory.getSize()) {
                if (clickedStack == null || clickedStack.getType() == Material.AIR) {
                    return;
                }
                if (LISTING.equals(state)) {
                    ExchangeGUI.addListingItem(clicker, clickedStack, event);
                } else {
                    ExchangeGUI.handleSelectedItem(clicker, clickedStack, ADD_BUY_ITEM.equals(state));
                }
                return;
            }
        }
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String displayName = meta.getDisplayName();
        if (displayName == null) {
            return;
        }
        block12 : switch (state) {
            case "main": {
                if (displayName.contains("\u4e0a\u5e02\u54c1\u79cd\u5217\u8868")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u8d27\u5e01\u5151\u6362")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openCurrencyExchangeMenu(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u6211\u7684\u4ea4\u6613\u8bb0\u5f55")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openMyHistory(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u516c\u544a\u680f")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openAnnouncements(plugin, clicker);
                    break;
                }
                break;
            }
            case "item_list": {
                if (displayName.contains("\u6210\u957f\u5546\u5e97")) {
                    clicker.closeInventory();
                    clicker.performCommand("actishop");
                    break;
                }
                if (displayName.contains(PAGE_PREV)) {
                    if (isDisabledNavigation(meta, 2400061)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1) - 1);
                    break;
                }
                if (displayName.contains(PAGE_NEXT)) {
                    if (isDisabledNavigation(meta, 2400062)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1) + 1);
                    break;
                }
                if (event.getRawSlot() == 46 && rawSlotIsTopInventory(event, 54)) {
                    ExchangeGUI.setBuyPage(plugin, clicker, !ExchangeGUI.isBuyPage(plugin, clicker));
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                if (event.getRawSlot() == ITEM_LIST_ACTION_SLOT && rawSlotIsTopInventory(event, 54)) {
                    guiNavigating.put(uuid, true);
                    if (ExchangeGUI.isBuyPage(plugin, clicker)) {
                        ExchangeGUI.openAddBuyItemMenu(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    } else {
                        plugin.getItemManager().normalizeCatalogDisplayNames();
                        ExchangeGUI.openListingMenu(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    }
                    break;
                }
                if (displayName.contains("\u641c\u7d22\u5546\u54c1")) {
                    guiNavigating.put(uuid, true);
                    plugin.getChatInputHandler().startMarketSearchInput(clicker);
                    break;
                }
                if (displayName.contains("\u8d27\u5e01\u5151\u6362")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openCurrencyExchangeMenu(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u6211\u7684\u4ea4\u6613\u8bb0\u5f55")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openMyHistory(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u516c\u544a\u680f")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openAnnouncements(plugin, clicker);
                    break;
                }
                if (displayName.contains(BACK_TO_PREVIOUS)) {
                    clicker.closeInventory();
                    clicker.performCommand("menu");
                    break;
                }
                Integer buyOrderId = ExchangeGUI.readOrderId(meta);
                if (buyOrderId != null && ExchangeGUI.isBuyPage(plugin, clicker)) {
                    Order buyOrder = plugin.getOrderManager().getOrder(buyOrderId);
                    if (buyOrder == null || buyOrder.getOrderType() != Order.OrderType.BUY || !buyOrder.isActive()) {
                        plugin.getTradeNoticeBuffer().manual(clicker, "\u00a7c\u8be5\u6c42\u8d2d\u5355\u5df2\u4e0d\u53ef\u7528\u3002");
                        guiNavigating.put(uuid, true);
                        ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                        break;
                    }
                    if (buyOrder.getPlayerUuid().equals(clicker.getUniqueId().toString())) {
                        if (event.getClick() == ClickType.LEFT) {
                            plugin.getTradeNoticeBuffer().manual(clicker,
                                plugin.getOrderManager().withdrawOrderQuantity(clicker, buyOrderId, 1));
                        } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                            plugin.getTradeNoticeBuffer().manual(clicker,
                                plugin.getOrderManager().withdrawOrderQuantity(
                                    clicker,
                                    buyOrderId,
                                    ExchangeGUI.readDisplayedQuantity(meta)
                                ));
                        } else {
                            break;
                        }
                    } else {
                        int quantity;
                        if (event.getClick() == ClickType.LEFT) {
                            quantity = 1;
                        } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                            ExchangeItem supplyItem = plugin.getItemManager().getItem(buyOrder.getItemId());
                            int available = supplyItem == null
                                ? 0
                                : plugin.getOrderManager().getSupplyPlan(clicker, supplyItem).availableQuantity();
                            quantity = Math.min(available, ExchangeGUI.readDisplayedQuantity(meta));
                            if (quantity <= 0) {
                                plugin.getTradeNoticeBuffer().manual(clicker,
                                    "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u53ef\u4f9b\u8d27\u7684\u7269\u54c1\u3002");
                            }
                        } else {
                            break;
                        }
                        if (quantity > 0) {
                            plugin.getTradeNoticeBuffer().manual(clicker,
                                plugin.getOrderManager().directSellToBuyOrder(clicker, buyOrderId, quantity));
                        }
                    }
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                Integer categoryItemId = meta.getPersistentDataContainer().get(
                    ExchangeGUI.categoryItemIdKey(plugin),
                    PersistentDataType.INTEGER
                );
                if (categoryItemId != null) {
                    ExchangeItem categoryItem = plugin.getItemManager().getItem(categoryItemId);
                    if (categoryItem != null) {
                        guiNavigating.put(uuid, true);
                        ExchangeGUI.openItemDetail(plugin, clicker, categoryItem);
                    }
                    break block12;
                }
                if (meta == null || meta.getLore() == null) break;
                for (String line : meta.getLore()) {
                    if (!line.startsWith("\u00a77ID: \u00a7f")) continue;
                    String idStr = line.replaceFirst("^.*?\u00a7f(\\d+).*$", "$1");
                    try {
                        int itemId = Integer.parseInt(idStr);
                        ExchangeItem item = plugin.getItemManager().getItem(itemId);
                        if (item == null) break block12;
                        guiNavigating.put(uuid, true);
                        ExchangeGUI.openItemDetail(plugin, clicker, item);
                    }
                    catch (NumberFormatException numberFormatException) {}
                    break block12;
                }
                break;
            }
            case "listing": {
                if (event.getRawSlot() == LISTING_CONFIRM_SLOT && rawSlotIsTopInventory(event, 27)) {
                    Map<String, Integer> pending = listingPending.get(uuid);
                    int total = 0;
                    if (pending != null) {
                        for (Integer count : pending.values()) {
                            total += count;
                        }
                    }
                    if (total <= 0) {
                        clicker.sendMessage("\u00a7c\u8bf7\u5148\u5c06\u8981\u4e0a\u67b6\u7684\u7269\u54c1\u653e\u5165\u8f93\u5165\u69fd\u3002");
                        break;
                    }
                    guiNavigating.put(uuid, true);
                    plugin.getChatInputHandler().startListingInput(clicker);
                    break;
                }
                if (event.getRawSlot() == LISTING_CANCEL_SLOT && rawSlotIsTopInventory(event, 27)) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.cancelListing(plugin, clicker);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                break;
            }
            case "item_detail": {
                if (event.getRawSlot() == ITEM_DETAIL_PREV_SLOT && rawSlotIsTopInventory(event, 54)) {
                    if (isDisabledNavigation(meta, 2400061)) break;
                    Integer detailId = guiItemId.get(uuid);
                    ExchangeItem detailItem = detailId == null ? null : plugin.getItemManager().getItem(detailId);
                    if (detailItem == null) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemDetail(
                        plugin,
                        clicker,
                        detailItem,
                        guiDetailPage.getOrDefault(uuid, 1) - 1
                    );
                    break;
                }
                if (event.getRawSlot() == ITEM_DETAIL_NEXT_SLOT && rawSlotIsTopInventory(event, 54)) {
                    if (isDisabledNavigation(meta, 2400062)) break;
                    Integer detailId = guiItemId.get(uuid);
                    ExchangeItem detailItem = detailId == null ? null : plugin.getItemManager().getItem(detailId);
                    if (detailItem == null) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemDetail(
                        plugin,
                        clicker,
                        detailItem,
                        guiDetailPage.getOrDefault(uuid, 1) + 1
                    );
                    break;
                }
                if (displayName.contains("\u8fd4\u56de")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                if (displayName.contains("\u6c42\u8d2d\u8be5\u7269\u54c1")) {
                    ExchangeItem buyItem;
                    Integer buyId = guiItemId.get(uuid);
                    if (buyId == null || (buyItem = plugin.getItemManager().getItem(buyId)) == null) break;
                    plugin.getChatInputHandler().startBuyInput(clicker, buyItem);
                    break;
                }
                if (displayName.contains("\u5feb\u901f\u4e0a\u67b6")) {
                    ExchangeItem itemQuick;
                    Integer quickId = guiItemId.get(uuid);
                    if (quickId == null || (itemQuick = plugin.getItemManager().getItem(quickId)) == null) break;
                    if (plugin.getItemManager().getSpecialCategory(itemQuick) != null) break;
                    String result = plugin.getOrderManager().quickSellAll(clicker, itemQuick);
                    plugin.getTradeNoticeBuffer().manual(clicker, result);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemDetail(
                        plugin,
                        clicker,
                        itemQuick,
                        guiDetailPage.getOrDefault(uuid, 1)
                    );
                    break;
                }
                if (displayName.contains("\u4e00\u952e\u4f9b\u8d27")) {
                    ExchangeItem supplyItem;
                    Integer supplyId = guiItemId.get(uuid);
                    if (supplyId == null || (supplyItem = plugin.getItemManager().getItem(supplyId)) == null) break;
                    plugin.getTradeNoticeBuffer().manual(clicker,
                        plugin.getOrderManager().supplyAllToBuyOrders(clicker, supplyItem));
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemDetail(
                        plugin,
                        clicker,
                        supplyItem,
                        guiDetailPage.getOrDefault(uuid, 1)
                    );
                    break;
                }
                if (displayName.contains("\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f")) {
                    boolean current = bulkBuyMode.getOrDefault(uuid, false);
                    bulkBuyMode.put(uuid, !current);
                    guiNavigating.put(uuid, true);
                    Integer toggleId = guiItemId.get(uuid);
                    ExchangeItem toggleItem = toggleId == null ? null : plugin.getItemManager().getItem(toggleId);
                    if (toggleItem != null) {
                        ExchangeGUI.openItemDetail(
                            plugin,
                            clicker,
                            toggleItem,
                            guiDetailPage.getOrDefault(uuid, 1)
                        );
                    }
                    break;
                }
                if (meta.getLore() == null) break;
                for (String line : meta.getLore()) {
                    if (!line.startsWith("\u00a70ORDER:")) continue;
                    try {
                        int orderId = Integer.parseInt(line.substring("\u00a70ORDER:".length()));
                        Order clickedOrder = plugin.getOrderManager().getOrder(orderId);
                        if (clickedOrder == null) break;
                        if (clickedOrder.getPlayerUuid().equals(clicker.getUniqueId().toString())) {
                            if (event.getClick() == ClickType.SHIFT_LEFT) {
                                int slotQty = ExchangeGUI.readDisplayedQuantity(meta);
                                plugin.getTradeNoticeBuffer().manual(clicker,
                                    plugin.getOrderManager().withdrawOrderQuantity(clicker, orderId, slotQty));
                            } else if (event.getClick() == ClickType.LEFT) {
                                plugin.getTradeNoticeBuffer().manual(clicker,
                                    plugin.getOrderManager().withdrawOrderQuantity(clicker, orderId, 1));
                            } else {
                                break;
                            }
                        } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                            int slotQty = ExchangeGUI.readDisplayedQuantity(meta);
                            String result;
                            if (clickedOrder.getOrderType() == Order.OrderType.BUY) {
                                Integer supplyItemId = guiItemId.get(uuid);
                                ExchangeItem supplyItem = supplyItemId == null
                                    ? null
                                    : plugin.getItemManager().getItem(supplyItemId);
                                int available = supplyItem == null
                                    ? 0
                                    : plugin.getOrderManager().getSupplyPlan(clicker, supplyItem).availableQuantity();
                                int qty = Math.min(available, slotQty);
                                result = qty > 0
                                    ? plugin.getOrderManager().directSellToBuyOrder(clicker, orderId, qty)
                                    : "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u53ef\u4f9b\u8d27\u7684\u7269\u54c1\u3002";
                            } else {
                                result = plugin.getOrderManager().directBuyFromSellOrder(clicker, orderId, slotQty);
                            }
                            plugin.getTradeNoticeBuffer().manual(clicker, result);
                        } else {
                            boolean bulk = bulkBuyMode.getOrDefault(uuid, false);
                            int qty = clickedOrder.getOrderType() == Order.OrderType.BUY
                                ? 1
                                : (bulk ? ExchangeGUI.readDisplayedQuantity(meta) : 1);
                            String result;
                            if (clickedOrder.getOrderType() == Order.OrderType.BUY) {
                                result = plugin.getOrderManager().directSellToBuyOrder(clicker, orderId, qty);
                            } else {
                                result = plugin.getOrderManager().directBuyFromSellOrder(clicker, orderId, qty);
                            }
                            plugin.getTradeNoticeBuffer().manual(clicker, result);
                        }
                        Integer currentItemId = guiItemId.get(uuid);
                        ExchangeItem currentItem = currentItemId == null ? null : plugin.getItemManager().getItem(currentItemId);
                        if (currentItem != null) {
                            guiNavigating.put(uuid, true);
                            ExchangeGUI.openItemDetail(
                                plugin,
                                clicker,
                                currentItem,
                                guiDetailPage.getOrDefault(uuid, 1)
                            );
                        }
                    }
                    catch (NumberFormatException numberFormatException) {}
                    break block12;
                }
                break;
            }
            case "order_book": {
                ExchangeItem item;
                if (!displayName.contains("\u8fd4\u56de")) break;
                guiNavigating.put(uuid, true);
                Integer id = guiItemId.get(uuid);
                if (id == null || (item = plugin.getItemManager().getItem(id)) == null) break;
                ExchangeGUI.openItemDetail(plugin, clicker, item);
                break;
            }
            case "my_history": {
                if (displayName.contains(PAGE_PREV)) {
                    if (isDisabledNavigation(meta, 2400061)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openMyHistory(plugin, clicker, guiPage.getOrDefault(uuid, 1) - 1);
                    break;
                }
                if (displayName.contains(PAGE_NEXT)) {
                    if (isDisabledNavigation(meta, 2400062)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openMyHistory(plugin, clicker, guiPage.getOrDefault(uuid, 1) + 1);
                    break;
                }
                if (displayName.contains("\u8fd4\u56de")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u6302\u5355 #")) {
                    for (String part : displayName.split(" ")) {
                        if (!part.startsWith("#")) continue;
                        try {
                            int orderId = Integer.parseInt(part.substring(1));
                            String result = plugin.getOrderManager().cancelOrder(clicker, orderId);
                            plugin.getTradeNoticeBuffer().manual(clicker, result);
                            guiNavigating.put(uuid, true);
                            ExchangeGUI.openMyHistory(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                        }
                        catch (NumberFormatException numberFormatException) {}
                        break block12;
                    }
                }
                break;
            }
            case "warehouse": {
                if (displayName.contains(PAGE_PREV)) {
                    if (isDisabledNavigation(meta, 2400061)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1) - 1);
                    break;
                }
                if (displayName.contains(PAGE_NEXT)) {
                    if (isDisabledNavigation(meta, 2400062)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1) + 1);
                    break;
                }
                if (displayName.contains("\u8fd4\u56de")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker);
                    break;
                }
                if (displayName.contains("\u4e00\u952e\u63d0\u53d6")) {
                    plugin.getStorageManager().withdrawWarehouse(clicker);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                if (displayName.contains("\u4ed3\u5e93\u661f\u5149\u70b9")) {
                    plugin.getStorageManager().withdrawWarehouseMoney(clicker);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                int warehouseSlot = event.getRawSlot();
                String itemBase64 = guiWarehouseEntries.getOrDefault(uuid, Map.of()).get(warehouseSlot);
                if (warehouseSlot < 9 || warehouseSlot > 44 || itemBase64 == null || itemBase64.isEmpty()) break;
                plugin.getStorageManager().withdrawWarehouseItem(clicker, itemBase64);
                guiNavigating.put(uuid, true);
                ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                break;
            }
            case "announcements": {
                if (displayName.contains(PAGE_PREV)) {
                    if (isDisabledNavigation(meta, 2400061)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openAnnouncements(plugin, clicker, guiPage.getOrDefault(uuid, 1) - 1);
                    break;
                }
                if (displayName.contains(PAGE_NEXT)) {
                    if (isDisabledNavigation(meta, 2400062)) break;
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openAnnouncements(plugin, clicker, guiPage.getOrDefault(uuid, 1) + 1);
                    break;
                }
                if (!displayName.contains("\u8fd4\u56de")) break;
                guiNavigating.put(uuid, true);
                ExchangeGUI.openItemList(plugin, clicker);
                break;
            }
            case "currency_exchange": {
                if (displayName.contains("\u5151\u6362 1 \u94bb\u77f3") && !displayName.contains("\u4f7f\u7528 1 \u94bb\u77f3")) {
                    clicker.sendMessage(plugin.exchangeMoneyForDiamond(clicker));
                    break;
                }
                if (displayName.contains("1 \u94bb\u77f3\u5151\u6362")) {
                    clicker.sendMessage(plugin.exchangeDiamondForMoney(clicker));
                    break;
                }
                if (!displayName.contains("\u8fd4\u56de")) break;
                guiNavigating.put(uuid, true);
                ExchangeGUI.openItemList(plugin, clicker);
                break;
            }
            case "add_item":
            case "add_buy_item": {
                if (displayName.contains("\u8fd4\u56de")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                } else if ((ADD_ITEM.equals(state) || ADD_BUY_ITEM.equals(state)) && displayName.contains("\u641c\u7d22\u6dfb\u52a0")) {
                    plugin.getChatInputHandler().startAddItemSearchInput(clicker, ADD_BUY_ITEM.equals(state));
                } else if (displayName.contains("\u4e0a\u5e02\u8bf4\u660e") || displayName.contains("\u6c42\u8d2d\u8bf4\u660e")) {
                    clicker.sendMessage("\u00a7e\u8bf7\u5c06\u80cc\u5305\u4e2d\u8981\u4e0a\u5e02\u7684\u7269\u54c1\u70b9\u51fb\u6216\u79fb\u5165\u8f93\u5165\u69fd\u3002");
                }
                break;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player;
        if (!(event.getWhoClicked() instanceof Player) || !guiState.containsKey((player = (Player)event.getWhoClicked()).getUniqueId())) {
            return;
        }
        String state = guiState.get(player.getUniqueId());
        event.setCancelled(true);
        if (!ADD_ITEM.equals(state) && !ADD_BUY_ITEM.equals(state)) {
            return;
        }
        if (!event.getRawSlots().contains(ADD_ITEM_INPUT_SLOT)) {
            return;
        }
        ItemStack cursor = event.getOldCursor();
        if (cursor == null || cursor.getType() == Material.AIR || MarketGuiItem.isMarked(cursor)) {
            return;
        }
        ExchangeGUI.handleSelectedItem(player, cursor, ADD_BUY_ITEM.equals(state));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ExchangeGUI.scheduleVoucherCleanup(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!MarketGuiItem.isMarked(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getItemDrop().remove();
        ExchangeGUI.scheduleVoucherCleanup(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(MarketGuiItem::isMarked);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ExchangeGUI.cancelCategoryIconRotation(uuid, event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            ExchangeGUI.scheduleVoucherCleanup(player);
            ExchangeGUI.saveMarketPage(StockExchangePlugin.getInstance(), player);
        }
        if (guiNavigating.remove(uuid) != null) {
            return;
        }
        String closedState = guiState.get(uuid);
        if (LISTING_PRICE.equals(closedState)) {
            // 单价输入期间不自动返还，返还/上架由输入回调显式处理
            return;
        }
        if (LISTING.equals(closedState)) {
            ExchangeGUI.cancelListing(StockExchangePlugin.getInstance(), (Player)event.getPlayer());
        }
        guiState.remove(uuid);
        guiItemId.remove(uuid);
        guiPage.remove(uuid);
        guiDetailPage.remove(uuid);
        bulkBuyMode.remove(uuid);
        guiWarehouseEntries.remove(uuid);
        guiSearchQueries.remove(uuid);
        listingPending.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ExchangeGUI.cancelCategoryIconRotation(uuid, null);
        ExchangeGUI.saveMarketPage(StockExchangePlugin.getInstance(), player);
        Map<String, Integer> pending = listingPending.remove(uuid);
        if (pending != null && !pending.isEmpty()) {
            ExchangeGUI.returnListingItems(player, pending);
        }
        guiState.remove(uuid);
        guiItemId.remove(uuid);
        guiPage.remove(uuid);
        guiDetailPage.remove(uuid);
        guiNavigating.remove(uuid);
        bulkBuyMode.remove(uuid);
        buyMode.remove(uuid);
        guiWarehouseEntries.remove(uuid);
        guiSearchQueries.remove(uuid);
        listingPending.remove(uuid);
    }

    private static void handleSelectedItem(Player player, ItemStack source, boolean buyOrder) {
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        if (plugin.denyGrowthAccess(player)) {
            return;
        }
        String expectedState = buyOrder ? ADD_BUY_ITEM : ADD_ITEM;
        if (!expectedState.equals(guiState.get(player.getUniqueId()))) {
            return;
        }
        if (source == null || source.getType() == Material.AIR || MarketGuiItem.isMarked(source)) {
            return;
        }
        ItemStack preview = source.clone();
        preview.setAmount(1);
        ItemManager.RegisterResult result = plugin.getItemManager().registerCatalogItem(
            player,
            preview,
            !buyOrder
        );
        player.sendMessage(result.getMessage());
        if (!result.isSuccess()) {
            return;
        }
        if (buyOrder) {
            plugin.getChatInputHandler().startBuyInput(player, result.getItem());
            return;
        }
        guiNavigating.put(player.getUniqueId(), true);
        ExchangeGUI.openItemList(plugin, player);
    }

    private static ItemStack createItem(Material material, String name, String ... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && lore.length > 0 && lore[0] != null) {
            meta.setLore(Arrays.asList(lore));
        }
        Integer customModelData = functionalModelData(name);
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack navigationItem(String name, boolean enabled, String lore) {
        String plainName = ChatColor.stripColor(name);
        String displayName = enabled ? name : "\u00a78" + (plainName == null ? name : plainName);
        int modelData = enabled
            ? (name.contains(PAGE_PREV) ? 2400011 : 2400012)
            : (name.contains(PAGE_PREV) ? 2400061 : 2400062);
        return createItemWithModelData(Material.ARROW, displayName, modelData, lore);
    }

    private static ItemStack createItemWithModelData(
        Material material,
        String name,
        int modelData,
        String... lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && lore.length > 0 && lore[0] != null) {
            meta.setLore(Arrays.asList(lore));
        }
        meta.setCustomModelData(modelData);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isDisabledNavigation(ItemMeta meta, int modelData) {
        return meta.hasCustomModelData() && meta.getCustomModelData() == modelData;
    }

    private static ItemStack createItem(ItemStack base, String name, String ... lore) {
        ItemMeta meta = base.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && lore.length > 0 && lore[0] != null) {
            meta.setLore(Arrays.asList(lore));
        }
        Integer customModelData = functionalModelData(name);
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
        base.setItemMeta(meta);
        return base;
    }

    private static Integer functionalModelData(String displayName) {
        String name = ChatColor.stripColor(displayName == null ? "" : displayName);
        if (name.contains("\u8fd4\u56de")) return 2400013;
        if (name.contains("\u4e0a\u4e00\u9875")) return 2400011;
        if (name.contains("\u4e0b\u4e00\u9875")) return 2400012;
        if (name.contains("\u641c\u7d22")) return 2400031;
        if (name.contains("\u516c\u544a")) return 2400027;
        if (name.contains("\u6210\u957f\u5546\u5e97") || name.contains("\u5e02\u573a")) return 2400022;
        if (name.contains("\u6dfb\u52a0\u5546\u54c1") || name.contains("\u6dfb\u52a0\u8bf4\u660e") || name.contains("\u6c42\u8d2d\u8be5\u7269\u54c1") || name.contains("\u6c42\u8d2d\u7269\u54c1") || name.contains("\u5feb\u901f\u4e0a\u67b6") || name.contains("\u4e0a\u67b6\u8be5\u7269\u54c1") || name.contains("\u4e0a\u67b6\u5546\u54c1")) return 2400016;
        if (name.contains("\u8d27\u5e01\u5151\u6362") || name.contains("\u4f7f\u7528") && name.contains("\u5151\u6362")) return 2400044;
        if (name.contains("\u4ea4\u6613\u8bb0\u5f55") || name.contains("\u8ba2\u5355") || name.contains("\u4e70\u5355") || name.contains("\u5356\u5355")) return 2400047;
        if (name.contains("\u516c\u544a\u680f") || name.contains("\u516c\u544a")) return 2400027;
        if (name.contains("\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f") || name.contains("\u51fa\u552e\u5546\u54c1\u9875\u9762") || name.contains("\u6c42\u8d2d\u5546\u54c1\u9875\u9762")) return 2400019;
        if (name.contains("\u4e00\u952e\u63d0\u53d6") || name.contains("\u4ed3\u5e93\u661f\u5149\u70b9")) return 2400045;
        if (name.contains("\u4e70\u5356") || name.contains("\u4ea4\u6613")) return 2400047;
        if (name.contains("\u6c42\u8d2d")) return 2400047;
        return null;
    }

    private static ItemStack createMarketVoucher(ItemStack baseItem, String itemName) {
        ItemStack voucher = baseItem.clone();
        voucher.setAmount(1);
        ItemMeta meta = voucher.getItemMeta();
        ArrayList<String> lore = new ArrayList<String>();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            lore.addAll(meta.getLore());
        }
        lore.add("\u00a78\u4ea4\u6613\u6240\u5c55\u793a\u51ed\u8bc1");
        meta.setDisplayName("\u00a7f" + itemName);
        meta.setLore(lore);
        voucher.setItemMeta(meta);
        MarketGuiItem.mark(voucher);
        return voucher;
    }

    private static ItemStack createHistoryIcon(
        ExchangeItem item,
        int quantity,
        boolean sellGlow,
        String title,
        String... lore
    ) {
        ItemStack baseItem = item == null ? null : ItemSerializer.itemFromBase64(item.getItemBase64());
        if (baseItem == null) {
            baseItem = new ItemStack(Material.BARRIER);
        }
        ItemStack icon = ExchangeGUI.createMarketVoucher(
            baseItem,
            item == null ? "\u672a\u77e5\u5546\u54c1" : ExchangeGUI.resolveDisplayName(item, baseItem)
        );
        ExchangeGUI.setDisplayAmount(icon, quantity);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            meta.setLore(Arrays.asList(lore));
            meta.setEnchantmentGlintOverride(sellGlow);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static String resolveHistoryItemName(ExchangeItem item, int itemId) {
        if (item == null) {
            return "ID:" + itemId;
        }
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        return baseItem == null ? item.getDisplayName() : ExchangeGUI.resolveDisplayName(item, baseItem);
    }

    private static void setDisplayAmount(ItemStack item, int quantity) {
        if (item == null) {
            return;
        }
        int maxStackSize = Math.max(1, Math.min(MarketListingLayout.MAX_DISPLAY_AMOUNT, item.getMaxStackSize()));
        item.setAmount(Math.max(1, Math.min(maxStackSize, quantity)));
    }

    private static Integer readOrderId(ItemMeta meta) {
        if (meta == null || meta.getLore() == null) {
            return null;
        }
        for (String line : meta.getLore()) {
            if (line == null || !line.startsWith("\u00a70ORDER:")) {
                continue;
            }
            try {
                return Integer.parseInt(line.substring("\u00a70ORDER:".length()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String shortUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "\u672a\u77e5 UUID";
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) + "..." : uuid;
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String resolveDisplayName(ExchangeItem item, ItemStack baseItem) {
        if (item != null && !ItemDisplayNames.isRawMaterialId(item.getDisplayName(), baseItem)) {
            return item.getDisplayName();
        }
        return ItemDisplayNames.resolve(baseItem);
    }

    private static boolean matchesCatalogSearch(StockExchangePlugin plugin, ExchangeItem item, String query) {
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        String resolvedName = baseItem == null ? item.getDisplayName() : ExchangeGUI.resolveDisplayName(item, baseItem);
        String keyName = baseItem == null ? "" : baseItem.getType().getKey().getKey();
        String typeName = baseItem == null ? "" : baseItem.getType().name();
        String material = item.getMaterial();
        if (baseItem != null) {
            if (material == null) {
                material = baseItem.getType().getKey().toString();
            } else {
                material = material + " " + typeName + " " + baseItem.getType().getKey();
            }
        }
        return MarketListingSearch.matches(query, item.getId(), resolvedName, item.getItemName(), material, keyName, typeName)
            || MarketListingSearch.matches(query, item.getId(), item.getDisplayName(), null, null, null, null);
    }

    private static String safeQueryForDisplay(String query) {
        String plain = ChatColor.stripColor(query == null ? "" : query);
        if (plain == null) {
            return "";
        }
        plain = plain.replace('\u00a7', ' ').trim();
        return plain.length() <= 40 ? plain : plain.substring(0, 40) + "...";
    }

    private static int readDisplayedQuantity(ItemMeta meta) {
        if (meta == null || meta.getLore() == null) {
            return 1;
        }
        for (String line : meta.getLore()) {
            String plain = org.bukkit.ChatColor.stripColor(line);
            if (plain == null || !plain.startsWith("\u8fd9\u683c\u6570\u91cf:")) {
                continue;
            }
            try {
                return Math.max(1, Integer.parseInt(plain.substring(plain.indexOf(':') + 1).trim()));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static void scheduleVoucherCleanup(Player player) {
        StockExchangePlugin plugin = StockExchangePlugin.getInstance();
        Bukkit.getScheduler().runTask(plugin, () -> ExchangeGUI.removeMarketVouchers(player));
        Bukkit.getScheduler().runTaskLater(plugin, () -> ExchangeGUI.removeMarketVouchers(player), 5L);
    }

    private static void removeMarketVouchers(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        for (int slot = 0; slot < player.getInventory().getSize(); ++slot) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (MarketGuiItem.isMarked(stack)) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (MarketGuiItem.isMarked(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        player.updateInventory();
    }

    private static String getStatusString(Order.OrderStatus status) {
        switch (status) {
            case OPEN: {
                return "\u00a7a\u6302\u5355\u4e2d";
            }
            case PARTIAL: {
                return "\u00a7e\u90e8\u5206\u6210\u4ea4";
            }
            case CLOSED: {
                return "\u00a77\u5df2\u6210\u4ea4";
            }
            case CANCELLED: {
                return "\u00a7c\u5df2\u53d6\u6d88";
            }
        }
        return "\u00a7f\u672a\u77e5";
    }

    private static boolean rawSlotIsTopInventory(InventoryClickEvent event, int topSize) {
        return event.getRawSlot() < topSize;
    }

    private static class MarketSnapshot {
        BigDecimal yesterdayClose = BigDecimal.ZERO;
        BigDecimal todayOpen = BigDecimal.ZERO;
        BigDecimal currentPrice = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.ZERO;
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal changePercent = BigDecimal.ZERO;

        private MarketSnapshot() {
        }
    }
}
