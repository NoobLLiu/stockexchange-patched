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
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.MarketGuiItem;
import com.github.exchange.util.TaxCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class ExchangeGUI
implements Listener {
    private static final Map<UUID, String> guiState = new HashMap<UUID, String>();
    private static final Map<UUID, Integer> guiItemId = new HashMap<UUID, Integer>();
    private static final Map<UUID, Integer> guiPage = new HashMap<UUID, Integer>();
    private static final Map<UUID, Boolean> guiNavigating = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Boolean> bulkBuyMode = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Boolean> buyMode = new HashMap<UUID, Boolean>();
    private static final Map<UUID, Map<Integer, String>> guiWarehouseEntries = new HashMap<UUID, Map<Integer, String>>();
    private static final Map<UUID, String> guiSearchQueries = new HashMap<UUID, String>();
    private static final String MAIN_MENU = "main";
    private static final String ITEM_LIST = "item_list";
    private static final String ITEM_DETAIL = "item_detail";
    private static final String ORDER_BOOK = "order_book";
    private static final String MY_HISTORY = "my_history";
    private static final String WAREHOUSE = "warehouse";
    private static final String ANNOUNCEMENTS = "announcements";
    private static final String CURRENCY_EXCHANGE = "currency_exchange";
    private static final String ADD_ITEM = "add_item";
    private static final String PAGE_PREV = "\u00a7e\u4e0a\u4e00\u9875";
    private static final String PAGE_NEXT = "\u00a7e\u4e0b\u4e00\u9875";
    private static final String BACK_TO_PREVIOUS = "\u00a7f\u8fd4\u56de\u4e0a\u4e00\u9875";
    private static final String SELL_MODE_NAME = "\u00a7a\u51fa\u552e\u6a21\u5f0f";
    private static final String BUY_MODE_NAME = "\u00a7c\u6c42\u8d2d\u6a21\u5f0f";
    private static final int ADD_ITEM_INPUT_SLOT = 13;
    private static final int ITEM_LIST_PREV_SLOT = 51;
    private static final int ITEM_LIST_SEPARATOR_SLOT = 44;
    private static final int ITEM_LIST_SEARCH_SLOT = 50;
    private static final int LARGE_PREV_SLOT = 45;
    private static final int LARGE_NEXT_SLOT = 52;
    private static final int LARGE_BACK_SLOT = 53;
    private static final int SMALL_BACK_SLOT = 26;

    private static String formatPrice(BigDecimal price) {
        return String.format("%.2f", price);
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
        ExchangeGUI.openItemList(plugin, player);
    }

    public static void openItemList(StockExchangePlugin plugin, Player player) {
        guiSearchQueries.remove(player.getUniqueId());
        ExchangeGUI.openItemList(plugin, player, 1);
    }

    private static void openItemList(StockExchangePlugin plugin, Player player, int page) {
        ExchangeGUI.openCategoryList(plugin, player, page);
    }

    private static void openCategoryList(StockExchangePlugin plugin, Player player, int page) {
        String query = guiSearchQueries.get(player.getUniqueId());
        List<ExchangeItem> items = new ArrayList<ExchangeItem>(plugin.getItemManager().getAllItems());
        if (query != null) {
            items.removeIf(item -> !ExchangeGUI.matchesCatalogSearch(plugin, item, query));
        }
        String title = query == null
            ? "\u00a76\u4ea4\u6613\u5e02\u573a - \u6309\u7269\u54c1\u79cd\u7c7b"
            : "\u00a76\u4ea4\u6613\u5e02\u573a - \u641c\u7d22\u7ed3\u679c";
        Inventory inv = Bukkit.createInventory(null, 54, title);
        int pageSize = 35;
        int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, items.size());
        int slot = 0;
        for (int idx = start; idx < end; ++idx) {
            ExchangeItem item = items.get(idx);
            ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (baseItem == null) continue;
            String displayName = ExchangeGUI.resolveDisplayName(item, baseItem);
            ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, displayName);
            ItemMeta meta = displayItem.getItemMeta();
            boolean isBuy = buyMode.getOrDefault(player.getUniqueId(), false);
            if (isBuy) {
                BigDecimal highestBuy = plugin.getOrderManager().getHighestBuyPrice(item.getId());
                int buyStock = 0;
                for (com.github.exchange.model.Order order : plugin.getOrderManager().getActiveOrders(item.getId(), com.github.exchange.model.Order.OrderType.BUY)) {
                    buyStock += order.getRemainingQty();
                }
                String buyPriceText = highestBuy == null ? "\u00a77\u6682\u65e0" : "\u00a7f" + ExchangeGUI.formatPrice(highestBuy);
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
                int stock = plugin.getOrderManager().getCurrentSellStock(item.getId());
                BigDecimal sevenDayChange = ExchangeGUI.getWindowedChangePercent(plugin, item.getId(), 7);
                BigDecimal monthChange = ExchangeGUI.getWindowedChangePercent(plugin, item.getId(), 30);
                String lowestPriceText = lowestPrice == null ? "\u00a77\u6682\u65e0" : "\u00a7f" + ExchangeGUI.formatPrice(lowestPrice);
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
        if (items.isEmpty() && query != null) {
            inv.setItem(22, ExchangeGUI.createItem(
                Material.BARRIER,
                "\u00a7c\u672a\u627e\u5230\u5339\u914d\u7684\u5546\u54c1",
                "\u00a77\u641c\u7d22\u5173\u952e\u8bcd: \u00a7f" + ExchangeGUI.safeQueryForDisplay(query),
                "\u00a77\u53ef\u4ee5\u641c\u7d22\u7269\u54c1\u540d\u79f0\u3001\u6750\u8d28 ID \u6216\u54c1\u79cd ID"
            ));
        }
        // Place "添加商品" at slot 35
        if (slot <= 35) {
            inv.setItem(35, ExchangeGUI.createItem(Material.EMERALD, "\u00a7a\u6dfb\u52a0\u5546\u54c1", "\u00a77\u5c06\u65b0\u7269\u54c1\u7c7b\u578b\u52a0\u5165\u5e02\u573a\u76ee\u5f55", "\u00a77\u4e0d\u4f1a\u6d88\u8017\u4f60\u80cc\u5305\u4e2d\u7684\u7269\u54c1"));
        }
        ItemStack separator = ExchangeGUI.createItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "\u00a77", new String[]{null});
        for (int separatorSlot = 36; separatorSlot <= ITEM_LIST_SEPARATOR_SLOT; ++separatorSlot) {
            inv.setItem(separatorSlot, separator);
        }
        ExchangeGUI.populateMarketFooter(plugin, player, inv, currentPage, totalPages);
        ExchangeGUI.finishOpeningItemList(player, inv, currentPage);
    }

    public static void openCatalogSearchResults(
        StockExchangePlugin plugin,
        Player player,
        String query,
        int page
    ) {
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            ExchangeGUI.openItemList(plugin, player);
            return;
        }
        guiSearchQueries.put(player.getUniqueId(), trimmedQuery);
        ExchangeGUI.openCategoryList(plugin, player, page);
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
        activityLore.add("\u00a77\u70b9\u51fb\u6253\u5f00\u6d3b\u8dc3\u5ea6\u5546\u5e97");
        MGActivitysPlugin actiPlugin = null;
        try {
            actiPlugin = MGActivitysPlugin.getInstance();
        } catch (Throwable ignore) {}
        if (actiPlugin != null) {
            try {
                ActivityData ad = actiPlugin.getActivityManager().getPlayerData(player.getName());
                activityLore.add("\u00a7e\u603b\u6d3b\u8dc3\u5ea6: \u00a7a" + ExchangeGUI.formatActivity(ad.getTotalActivity()));
                activityLore.add("\u00a7e\u52a8\u6001\u6d3b\u8dc3\u5ea6: \u00a7a" + ExchangeGUI.formatActivity(ad.getDynamicActivity()));
            } catch (Throwable ignore) {}
        }
        inv.setItem(45, ExchangeGUI.createItem(Material.EXPERIENCE_BOTTLE, "\u00a7d\u6d3b\u8dc3\u5ea6\u5546\u5e97", activityLore.toArray(new String[0])));
        boolean modeIsBuy = buyMode.getOrDefault(player.getUniqueId(), false);
        String currentModeName = modeIsBuy ? BUY_MODE_NAME : SELL_MODE_NAME;
        String nextModeName = modeIsBuy ? SELL_MODE_NAME : BUY_MODE_NAME;
        inv.setItem(46, ExchangeGUI.createItem(
            Material.REDSTONE_TORCH,
            currentModeName,
            "\u00a77\u5f53\u524d\u6a21\u5f0f\uff1a" + (modeIsBuy ? "\u00a7c\u663e\u793a\u6c42\u8d2d\u4ef7\u683c" : "\u00a7a\u663e\u793a\u51fa\u552e\u4ef7\u683c"),
            "\u00a7e\u70b9\u51fb\u5207\u6362\u5230" + nextModeName
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

    private static void finishOpeningItemList(Player player, Inventory inv, int currentPage) {
        guiState.put(player.getUniqueId(), ITEM_LIST);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), currentPage);
        player.openInventory(inv);
    }

    public static void openCurrencyExchangeMenu(StockExchangePlugin plugin, Player player) {
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
        ExchangeGUI.openAddItemMenu(plugin, player, guiPage.getOrDefault(player.getUniqueId(), 1));
    }

    public static void openAddItemMenu(StockExchangePlugin plugin, Player player, int sourcePage) {
        Inventory inv = Bukkit.createInventory(null, 27, "\u00a76\u6dfb\u52a0\u5546\u54c1");
        inv.setItem(4, ExchangeGUI.createItem(Material.EMERALD, "\u00a7a\u6dfb\u52a0\u8bf4\u660e", "\u00a77\u5c06\u8981\u65b0\u589e\u7684\u7269\u54c1\u653e\u5165\u4e0b\u65b9\u8f93\u5165\u69fd", "\u00a77\u53ea\u4f1a\u628a\u8be5\u7269\u54c1\u52a0\u5165\u5e02\u573a\u76ee\u5f55\uff0c\u4e0d\u4f1a\u6d88\u8017\u80cc\u5305\u7269\u54c1"));
        inv.setItem(ADD_ITEM_INPUT_SLOT, null);
        inv.setItem(SMALL_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u5217\u8868"));
        inv.setItem(22, ExchangeGUI.createItem(Material.NAME_TAG, "\u00a7e\u641c\u7d22\u6dfb\u52a0", "\u00a77\u8f93\u5165\u7269\u54c1\u540d\u79f0\u6216 ID \u641c\u7d22\u6240\u6709\u539f\u7248\u7269\u54c1", "\u00a77\u70b9\u51fb\u6253\u5f00\u641c\u7d22\u8f93\u5165"));
        guiState.put(player.getUniqueId(), ADD_ITEM);
        guiItemId.remove(player.getUniqueId());
        guiPage.put(player.getUniqueId(), Math.max(1, sourcePage));
        player.openInventory(inv);
    }

    public static void openBedrockCurrencyExchangeForm(StockExchangePlugin plugin, Player player) {
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
                openBedrockItemListForm(plugin, player);
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
        boolean bulk = bulkBuyMode.getOrDefault(player.getUniqueId(), false);
        boolean isBuy = buyMode.getOrDefault(player.getUniqueId(), false);
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        if (baseItem == null) {
            player.sendMessage("\u00a7c\u7269\u54c1\u6570\u636e\u635f\u574f\uff0c\u65e0\u6cd5\u6253\u5f00\u8be6\u60c5\u3002");
            return;
        }
        String itemDisplayName = ExchangeGUI.resolveDisplayName(item, baseItem);
        String modeSuffix = isBuy ? " \u00a77| \u00a7c\u6c42\u8d2d\u6a21\u5f0f" : " \u00a77| \u00a7a\u51fa\u552e\u6a21\u5f0f";
        Inventory inv = Bukkit.createInventory(null, 54, "\u00a76\u54c1\u79cd\u8be6\u60c5: " + itemDisplayName + modeSuffix);
        ItemStack glass = ExchangeGUI.createItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "\u00a77", new String[]{null});
        int slot = 0;
        if (isBuy) {
            List<Order> buyOrders = new ArrayList<Order>(plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.BUY));
            buyOrders.sort(Comparator.comparing(Order::getPrice).reversed().thenComparing(Order::getCreatedAt));
            for (Order buyOrder : buyOrders) {
                if (slot >= 45) break;
                int displayedQuantity = Math.min(buyOrder.getRemainingQty(), MarketListingLayout.MAX_DISPLAY_AMOUNT);
                ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, itemDisplayName);
                ExchangeGUI.setDisplayAmount(displayItem, displayedQuantity);
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<String>();
                    lore.add("\u00a77\u7269\u54c1: \u00a7f" + itemDisplayName);
                    lore.add("\u00a77\u4e70\u5bb6: \u00a7f" + buyOrder.getPlayerName());
                    lore.add("\u00a77\u6c42\u8d2d\u4ef7: \u00a7f" + ExchangeGUI.formatPrice(buyOrder.getPrice()));
                    lore.add("\u00a77\u8fd9\u683c\u6570\u91cf: \u00a7f" + displayedQuantity);
                    lore.add("\u00a77\u8be5\u6c42\u8d2d\u5355\u5269\u4f59: \u00a7f" + buyOrder.getRemainingQty());
                    lore.add("");
                    if (buyOrder.getPlayerUuid().equals(player.getUniqueId().toString())) {
                        lore.add("\u00a7e\u70b9\u51fb\u53d6\u6d88\u8be5\u6c42\u8d2d\u5355");
                    } else if (bulk) {
                        lore.add("\u00a7e\u70b9\u51fb\u51fa\u552e " + displayedQuantity + " \u4e2a");
                    } else {
                        lore.add("\u00a7e\u70b9\u51fb\u51fa\u552e 1 \u4e2a");
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
            inv.setItem(45, ExchangeGUI.createItem(Material.HOPPER, "\u00a7e\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f \u00a77| " + (bulk ? "\u00a7c\u6279\u91cf\u8d2d\u4e70" : "\u00a7a\u5355\u4e2a\u8d2d\u4e70"), "\u00a77\u70b9\u51fb\u5207\u6362"));
            inv.setItem(49, ExchangeGUI.createItem(Material.REDSTONE, "\u00a7b\u6c42\u8d2d\u8be5\u7269\u54c1", "\u00a77\u8f93\u5165\u4ef7\u683c\u548c\u6570\u91cf\u53d1\u8d77\u6c42\u8d2d\u5355"));
            inv.setItem(LARGE_NEXT_SLOT, glass);
            inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u5217\u8868"));
        } else {
            List<Order> sellOrders = new ArrayList<Order>(plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.SELL));
            sellOrders.sort(Comparator.comparing(Order::getPrice).thenComparing(Order::getCreatedAt));
            for (MarketListingLayout.Slot listingSlot : MarketListingLayout.expand(sellOrders)) {
                if (slot >= 45) break;
                Order sellOrder = listingSlot.order();
                int displayedQuantity = listingSlot.amount();
                ItemStack displayItem = ExchangeGUI.createMarketVoucher(baseItem, itemDisplayName);
                ExchangeGUI.setDisplayAmount(displayItem, displayedQuantity);
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    ArrayList<String> lore = new ArrayList<String>();
                    lore.add("\u00a77\u7269\u54c1: \u00a7f" + itemDisplayName);
                    lore.add("\u00a77\u5356\u5bb6: \u00a7f" + sellOrder.getPlayerName());
                    lore.add("\u00a77\u5355\u4ef7: \u00a7f" + ExchangeGUI.formatPrice(sellOrder.getPrice()));
                    lore.add("\u00a77\u8fd9\u683c\u6570\u91cf: \u00a7f" + displayedQuantity);
                    lore.add("\u00a77\u8be5\u5356\u5355\u5269\u4f59: \u00a7f" + sellOrder.getRemainingQty());
                    lore.add("");
                    if (sellOrder.getPlayerUuid().equals(player.getUniqueId().toString())) {
                        lore.add("\u00a7e\u70b9\u51fb\u4e0b\u67b6\u5e76\u53d6\u56de\u8be5\u5356\u5355");
                    } else if (bulk) {
                        lore.add("\u00a7e\u70b9\u51fb\u8d2d\u4e70 " + displayedQuantity + " \u4e2a");
                    } else {
                        lore.add("\u00a7e\u70b9\u51fb\u8d2d\u4e70 1 \u4e2a");
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
            inv.setItem(51, ExchangeGUI.createItem(Material.GLOWSTONE_DUST, "\u00a76\u5feb\u901f\u4e0a\u67b6", "\u00a77\u4e00\u952e\u6309\u6700\u4f4e\u4ef7\u4e0a\u67b6\u80cc\u5305\u4e2d\u6240\u6709\u540c\u7c7b\u578b\u5546\u54c1"));
            inv.setItem(LARGE_NEXT_SLOT, ExchangeGUI.createItem(Material.EMERALD_BLOCK, "\u00a7a\u4e0a\u67b6\u8be5\u7269\u54c1", "\u00a77\u8f93\u5165\u4ef7\u683c\u548c\u6570\u91cf\u8fdb\u884c\u4e0a\u67b6"));
            inv.setItem(LARGE_BACK_SLOT, ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u5217\u8868"));
        }
        guiState.put(player.getUniqueId(), ITEM_DETAIL);
        guiItemId.put(player.getUniqueId(), item.getId());
        player.openInventory(inv);
    }

    public static void openOrderBook(StockExchangePlugin plugin, Player player, ExchangeItem item) {
        Inventory inv = Bukkit.createInventory(null, (int)54, (String)("\u00a76\u76d8\u53e3: " + item.getDisplayName()));
        List<Order> buyOrders = plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.BUY);
        List<Order> sellOrders = plugin.getOrderManager().getActiveOrders(item.getId(), Order.OrderType.SELL);
        ItemStack buyTitle = ExchangeGUI.createItem(new ItemStack(Material.RED_STAINED_GLASS_PANE), "\u00a7c=== \u4e70\u5355 ===", new String[]{null});
        inv.setItem(0, buyTitle);
        int slot = 1;
        for (int i = 0; i < Math.min(buyOrders.size(), 21); ++i) {
            Order order = buyOrders.get(i);
            ItemStack orderItem = ExchangeGUI.createItem(Material.RED_DYE, "\u00a7c\u4e70\u5355 #" + order.getId(), "\u00a77\u73a9\u5bb6: " + order.getPlayerName() + " (" + order.getPlayerUuid().substring(0, 8) + "...)", "\u00a77\u4ef7\u683c: \u00a7f" + ExchangeGUI.formatPrice(order.getPrice()), "\u00a77\u6570\u91cf: \u00a7f" + order.getRemainingQty() + "/" + order.getQuantity(), "\u00a77\u5df2\u6210\u4ea4: \u00a7f" + order.getFilledQty(), "\u00a77\u72b6\u6001: " + ExchangeGUI.getStatusString(order.getStatus()));
            inv.setItem(slot++, orderItem);
        }
        ItemStack separator = ExchangeGUI.createItem(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), "\u00a77--- \u76d8\u53e3 ---", new String[]{null});
        inv.setItem(22, separator);
        ItemStack sellTitle = ExchangeGUI.createItem(new ItemStack(Material.GREEN_STAINED_GLASS_PANE), "\u00a7a=== \u5356\u5355 ===", new String[]{null});
        inv.setItem(23, sellTitle);
        slot = 24;
        for (int i = 0; i < Math.min(sellOrders.size(), 21); ++i) {
            Order order = sellOrders.get(i);
            ItemStack orderItem = ExchangeGUI.createItem(Material.GREEN_DYE, "\u00a7a\u5356\u5355 #" + order.getId(), "\u00a77\u73a9\u5bb6: " + order.getPlayerName() + " (" + order.getPlayerUuid().substring(0, 8) + "...)", "\u00a77\u4ef7\u683c: \u00a7f" + ExchangeGUI.formatPrice(order.getPrice()), "\u00a77\u6570\u91cf: \u00a7f" + order.getRemainingQty() + "/" + order.getQuantity(), "\u00a77\u5df2\u6210\u4ea4: \u00a7f" + order.getFilledQty(), "\u00a77\u72b6\u6001: " + ExchangeGUI.getStatusString(order.getStatus()));
            inv.setItem(slot++, orderItem);
        }
        ItemStack backItem = ExchangeGUI.createItem(Material.ARROW, BACK_TO_PREVIOUS, "\u00a77\u8fd4\u56de\u54c1\u79cd\u8be6\u60c5");
        inv.setItem(LARGE_BACK_SLOT, backItem);
        guiState.put(player.getUniqueId(), ORDER_BOOK);
        guiItemId.put(player.getUniqueId(), item.getId());
        player.openInventory(inv);
    }

    public static void openMyOrders(StockExchangePlugin plugin, Player player) {
        ExchangeGUI.openMyHistory(plugin, player, 1);
    }

    private static void openMyOrders(StockExchangePlugin plugin, Player player, int page) {
        ExchangeGUI.openMyHistory(plugin, player, page);
    }

    public static void openMyTrades(StockExchangePlugin plugin, Player player) {
        ExchangeGUI.openMyHistory(plugin, player, 1);
    }

    private static void openMyTrades(StockExchangePlugin plugin, Player player, int page) {
        ExchangeGUI.openMyHistory(plugin, player, page);
    }

    public static void openMyHistory(StockExchangePlugin plugin, Player player) {
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
        ExchangeGUI.openWarehouse(plugin, player, 1);
    }

    public static void openAnnouncements(StockExchangePlugin plugin, Player player) {
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
        ItemStack moneyItem = ExchangeGUI.createItem(Material.GOLD_INGOT, "\u00a7e\u4ed3\u5e93\u91d1\u5e01", "\u00a77\u5f53\u524d\u53ef\u63d0\u53d6: \u00a7f" + String.format("%.2f", money));
        inv.setItem(4, moneyItem);
        Map<String, Integer> snapshot = plugin.getStorageManager().getWarehouseSnapshot();
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
        ItemStack withdrawItem = ExchangeGUI.createItem(Material.CHEST, "\u00a7a\u4e00\u952e\u63d0\u53d6", "\u00a77\u63d0\u53d6\u4ed3\u5e93\u4e2d\u7684\u7269\u54c1\u548c\u91d1\u5e01");
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
        if (ADD_ITEM.equals(state)) {
            Inventory topInventory = event.getView().getTopInventory();
            int rawSlot = event.getRawSlot();
            if (rawSlot == ADD_ITEM_INPUT_SLOT) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    ExchangeGUI.registerCatalogPreview(clicker, cursor);
                    return;
                }
                return;
            }
            ItemStack clickedStack = event.getCurrentItem();
            if (rawSlot >= topInventory.getSize()) {
                if (clickedStack == null || clickedStack.getType() == Material.AIR) {
                    return;
                }
                ExchangeGUI.registerCatalogPreview(clicker, clickedStack);
                return;
            }
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
                if (displayName.contains("\u6d3b\u8dc3\u5ea6\u5546\u5e97")) {
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
                if (displayName.contains("\u641c\u7d22\u5546\u54c1")) {
                    guiNavigating.put(uuid, true);
                    plugin.getChatInputHandler().startMarketSearchInput(clicker);
                    break;
                }
                if (displayName.contains("\u51fa\u552e\u6a21\u5f0f") || displayName.contains("\u6c42\u8d2d\u6a21\u5f0f")) {
                    boolean current = buyMode.getOrDefault(uuid, false);
                    buyMode.put(uuid, !current);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                if (displayName.contains("\u6dfb\u52a0\u5546\u54c1")) {
                    guiNavigating.put(uuid, true);
                    plugin.getItemManager().normalizeCatalogDisplayNames();
                    ExchangeGUI.openAddItemMenu(plugin, clicker, guiPage.getOrDefault(uuid, 1));
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
                // Slot 35 - 添加商品 (raw slot fallback)
                if (event.getRawSlot() == 35 && rawSlotIsTopInventory(event, 54)) {
                    guiNavigating.put(uuid, true);
                    plugin.getItemManager().normalizeCatalogDisplayNames();
                    ExchangeGUI.openAddItemMenu(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                // Slot 46 fallback for mode toggle
                if (event.getRawSlot() == 46 && rawSlotIsTopInventory(event, 54)) {
                    boolean current = buyMode.getOrDefault(uuid, false);
                    buyMode.put(uuid, !current);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
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
            case "item_detail": {
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
                if (displayName.contains("\u4e0a\u67b6\u8be5\u7269\u54c1")) {
                    ExchangeItem item3;
                    Integer id3 = guiItemId.get(uuid);
                    if (id3 == null || (item3 = plugin.getItemManager().getItem(id3)) == null) break;
                    plugin.getChatInputHandler().startSellInput(clicker, item3);
                    break;
                }
                if (displayName.contains("\u5feb\u901f\u4e0a\u67b6")) {
                    ExchangeItem itemQuick;
                    Integer quickId = guiItemId.get(uuid);
                    if (quickId == null || (itemQuick = plugin.getItemManager().getItem(quickId)) == null) break;
                    String result = plugin.getOrderManager().quickSellAll(clicker, itemQuick);
                    clicker.sendMessage(result);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemDetail(plugin, clicker, itemQuick);
                    break;
                }
                if (displayName.contains("\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f")) {
                    boolean current = bulkBuyMode.getOrDefault(uuid, false);
                    bulkBuyMode.put(uuid, !current);
                    guiNavigating.put(uuid, true);
                    Integer toggleId = guiItemId.get(uuid);
                    ExchangeItem toggleItem = toggleId == null ? null : plugin.getItemManager().getItem(toggleId);
                    if (toggleItem != null) {
                        ExchangeGUI.openItemDetail(plugin, clicker, toggleItem);
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
                            String result = plugin.getOrderManager().cancelOrder(clicker, orderId);
                            clicker.sendMessage(result);
                        } else {
                            boolean bulk = bulkBuyMode.getOrDefault(uuid, false);
                            int qty = bulk ? ExchangeGUI.readDisplayedQuantity(meta) : 1;
                            String result;
                            if (clickedOrder.getOrderType() == Order.OrderType.BUY) {
                                result = plugin.getOrderManager().directSellToBuyOrder(clicker, orderId, qty);
                            } else {
                                result = plugin.getOrderManager().directBuyFromSellOrder(clicker, orderId, qty);
                            }
                            clicker.sendMessage(result);
                        }
                        Integer currentItemId = guiItemId.get(uuid);
                        ExchangeItem currentItem = currentItemId == null ? null : plugin.getItemManager().getItem(currentItemId);
                        if (currentItem != null) {
                            guiNavigating.put(uuid, true);
                            ExchangeGUI.openItemDetail(plugin, clicker, currentItem);
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
                            clicker.sendMessage(result);
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
                if (displayName.contains("\u4ed3\u5e93\u91d1\u5e01")) {
                    plugin.getStorageManager().withdrawWarehouseMoney(clicker);
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openWarehouse(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                    break;
                }
                int rawSlot = event.getRawSlot();
                String itemBase64 = guiWarehouseEntries.getOrDefault(uuid, Map.of()).get(rawSlot);
                if (rawSlot < 9 || rawSlot > 44 || itemBase64 == null || itemBase64.isEmpty()) break;
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
            case "add_item": {
                if (displayName.contains("\u8fd4\u56de")) {
                    guiNavigating.put(uuid, true);
                    ExchangeGUI.openItemList(plugin, clicker, guiPage.getOrDefault(uuid, 1));
                } else if (displayName.contains("\u641c\u7d22\u6dfb\u52a0")) {
                    plugin.getChatInputHandler().startAddItemSearchInput(clicker);
                } else if (displayName.contains("\u4e0a\u5e02\u8bf4\u660e")) {
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
        if (!ADD_ITEM.equals(state)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
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
        if (event.getPlayer() instanceof Player player) {
            ExchangeGUI.scheduleVoucherCleanup(player);
        }
        if (guiNavigating.remove(uuid) != null) {
            return;
        }
        guiState.remove(uuid);
        guiItemId.remove(uuid);
        guiPage.remove(uuid);
        guiWarehouseEntries.remove(uuid);
        guiSearchQueries.remove(uuid);
    }

    private static void registerCatalogPreview(Player player, ItemStack source) {
        if (!ADD_ITEM.equals(guiState.get(player.getUniqueId()))) {
            return;
        }
        if (source == null || source.getType() == Material.AIR || MarketGuiItem.isMarked(source)) {
            return;
        }
        ItemStack preview = source.clone();
        preview.setAmount(1);
        ItemManager.RegisterResult result = StockExchangePlugin.getInstance().getItemManager().registerCatalogItem(player, preview);
        player.sendMessage(result.getMessage());
        if (!result.isSuccess()) {
            return;
        }
        guiNavigating.put(player.getUniqueId(), true);
        ExchangeGUI.openItemList(StockExchangePlugin.getInstance(), player, guiPage.getOrDefault(player.getUniqueId(), 1));
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
        if (name.contains("\u6d3b\u8dc3\u5ea6\u5546\u5e97") || name.contains("\u5e02\u573a")) return 2400022;
        if (name.contains("\u6dfb\u52a0\u5546\u54c1") || name.contains("\u6dfb\u52a0\u8bf4\u660e") || name.contains("\u5feb\u901f\u4e0a\u67b6") || name.contains("\u4e0a\u67b6\u8be5\u7269\u54c1")) return 2400016;
        if (name.contains("\u8d27\u5e01\u5151\u6362") || name.contains("\u4f7f\u7528") && name.contains("\u5151\u6362")) return 2400044;
        if (name.contains("\u4ea4\u6613\u8bb0\u5f55") || name.contains("\u8ba2\u5355") || name.contains("\u4e70\u5355") || name.contains("\u5356\u5355")) return 2400047;
        if (name.contains("\u516c\u544a\u680f") || name.contains("\u516c\u544a")) return 2400027;
        if (name.contains("\u5207\u6362\u8d2d\u4e70\u6a21\u5f0f")) return 2400019;
        if (name.contains("\u4e00\u952e\u63d0\u53d6") || name.contains("\u4ed3\u5e93\u91d1\u5e01")) return 2400045;
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
        item.setAmount(Math.max(1, Math.min(MarketListingLayout.MAX_DISPLAY_AMOUNT, quantity)));
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
