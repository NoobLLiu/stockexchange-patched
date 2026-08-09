package com.github.exchange.web;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.gui.MarketListingLayout;
import com.github.exchange.gui.MarketListingSearch;
import com.github.exchange.manager.ItemManager;
import com.github.exchange.manager.SupplyPlanner;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.MarketPageFilter;
import com.github.exchange.util.SpecialCategory;
import com.github.exchange.util.TaxCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 网页端市场导出 API（WebMarketManager）。
 *
 * <p>目标：把游戏内每个菜单/按钮的功能导出给网页端，行为与游戏内一致：
 * <ul>
 *   <li>网页资产一律走「个人仓库通道」：买单从货币仓库扣款、卖单从个人物品仓库扣货，
 *       成交/撤单/部分取回也退回仓库；游戏内操作不触碰仓库（除显式存取），两侧永不混用在线背包。</li>
 *   <li>检测与游戏内相同：成长等级、数量上限、价格区间/步进、停牌、持仓/余额、自成交、
 *       托管一致性、每日新增商品上限（非管理员）、管理员权限由调用方以 admin 标志显式传入。</li>
 *   <li>撮合/结算复用 OrderManager 的同一引擎（价格-时间优先、卖家税、托管校验、成交落库）。</li>
 *   <li>并发：写操作按玩家 UUID 加可重入锁，并强制回到服务器主线程串行执行
 *       （callSyncMethod），同一个人/多个人的并发请求不会交叉移动资产。</li>
 * </ul>
 */
public class WebMarketManager {

    private static final long LISTING_VISIBILITY_MILLIS = TimeUnit.HOURS.toMillis(24L);
    private static final long SELL_VOLUME_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(7L);

    private final StockExchangePlugin plugin;
    private final Map<String, ReentrantLock> playerLocks = new ConcurrentHashMap<String, ReentrantLock>();

    public WebMarketManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    // ===================== 只读：商品列表/详情/盘口/记录/仓库/信息 =====================

    /** 全部上市品种（含行情与停牌状态）。兼容旧版调用。 */
    public Map<String, Object> listItems() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ExchangeItem item : plugin.getItemManager().getAllItems()) {
            items.add(itemView(item, false));
        }
        return ok(map(
            "items", items,
            "currency_name", plugin.getCurrencyName(),
            "tax_rate_percent", plugin.getTaxRatePercent()
        ));
    }

    /**
     * 商品列表页：与游戏内“出售/求购商品”页面相同的可见性过滤、排序与搜索。
     *
     * @param uuid     玩家 UUID（用于判断是否为本人，不影响列表可见性）
     * @param buyPage  true=求购商品页，false=出售商品页
     * @param query    搜索关键词（可为 null/空）
     * @param page     页码，从 1 开始
     * @param pageSize 每页数量（默认 35，与游戏一致；1-100）
     */
    public Map<String, Object> listItems(String uuid, boolean buyPage, String query, int page, int pageSize) {
        boolean explicitSearch = query != null && !query.isBlank();
        plugin.getItemManager().ensureSpecialCategories();
        List<ExchangeItem> items = new ArrayList<ExchangeItem>(plugin.getItemManager().getAllItems());
        long now = System.currentTimeMillis();
        long sellVolumeSince = now - SELL_VOLUME_WINDOW_MILLIS;
        Order.OrderType pageOrderType = buyPage ? Order.OrderType.BUY : Order.OrderType.SELL;
        Map<Integer, Long> activeQuantities = new HashMap<Integer, Long>();
        Map<Integer, Long> latestOrderCreatedAt = new HashMap<Integer, Long>();
        Map<Integer, Long> recentSellVolumes = new HashMap<Integer, Long>();
        items.removeIf(item -> {
            if (plugin.getItemManager().getSpecialCategory(item) != null) {
                long activeQuantity = MarketPageFilter.activeRemainingQuantity(
                    plugin.getOrderManager().getActiveOrders(item.getId(), pageOrderType)
                );
                activeQuantities.put(item.getId(), activeQuantity);
                if (!buyPage) {
                    recentSellVolumes.put(
                        item.getId(),
                        plugin.getStorageManager().getTradeVolumeSince(item.getId(), sellVolumeSince)
                    );
                }
                return false;
            }
            ItemStack rawItemStack = ItemSerializer.itemFromBase64(item.getItemBase64());
            if (rawItemStack != null && SpecialCategory.of(rawItemStack) != null) {
                return true;
            }
            long activeQuantity = MarketPageFilter.activeRemainingQuantity(
                plugin.getOrderManager().getActiveOrders(item.getId(), pageOrderType)
            );
            long latestOrderAt = plugin.getStorageManager().getLatestOrderCreatedAt(item.getId(), pageOrderType);
            if (!buyPage && latestOrderAt <= 0L) {
                long catalogActivityAt = 0L;
                if (item.getCreatedAt() != null) {
                    catalogActivityAt = Math.max(catalogActivityAt, item.getCreatedAt().getTime());
                }
                if (item.getLastStockedAt() != null) {
                    catalogActivityAt = Math.max(catalogActivityAt, item.getLastStockedAt().getTime());
                }
                latestOrderAt = MarketPageFilter.latestVisibilityAt(latestOrderAt, catalogActivityAt);
            }
            activeQuantities.put(item.getId(), activeQuantity);
            latestOrderCreatedAt.put(item.getId(), latestOrderAt);
            if (!buyPage) {
                recentSellVolumes.put(
                    item.getId(),
                    plugin.getStorageManager().getTradeVolumeSince(item.getId(), sellVolumeSince)
                );
            }
            return !MarketPageFilter.isVisibleForQuery(
                activeQuantity,
                latestOrderAt,
                now,
                LISTING_VISIBILITY_MILLIS,
                explicitSearch
            );
        });
        if (buyPage) {
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
            items.sort((a, b) -> {
                int cmp = Long.compare(
                    recentSellVolumes.getOrDefault(b.getId(), 0L),
                    recentSellVolumes.getOrDefault(a.getId(), 0L)
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
        }
        if (explicitSearch) {
            items.removeIf(item -> plugin.getItemManager().getSpecialCategory(item) == null
                && !matchesCatalogSearch(item, query));
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 35;
        }
        if (page < 1) {
            page = 1;
        }
        int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int currentPage = Math.min(page, totalPages);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, items.size());
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (int idx = start; idx < end; ++idx) {
            views.add(itemView(items.get(idx), buyPage));
        }
        return ok(map(
            "items", views,
            "page", currentPage,
            "page_size", pageSize,
            "total_pages", totalPages,
            "total_items", items.size(),
            "buy_page", buyPage,
            "query", query == null ? "" : query
        ));
    }

    /** 品种详情（兼容旧版调用）。 */
    public Map<String, Object> itemDetail(int itemId) {
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("item", itemView(item, false));
        data.put("status", statusView(plugin.getItemManager().getItemStatus(itemId)));
        Trade last = plugin.getTradeManager().getLastTrade(itemId);
        if (last != null) {
            data.put("last_price", last.getPrice());
            data.put("last_traded_at", last.getTradedAt());
        }
        return ok(data);
    }

    /** 品种详情页：包含游戏内详情页的挂单格、本人/他人标识、可执行按钮与供货计划。 */
    public Map<String, Object> itemDetail(String uuid, int itemId, boolean buyPage, int page, int pageSize) {
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        if (baseItem == null) {
            return fail("物品数据损坏，无法打开详情。");
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("item", itemView(item, buyPage));
        data.put("status", statusView(plugin.getItemManager().getItemStatus(itemId)));
        Trade last = plugin.getTradeManager().getLastTrade(itemId);
        if (last != null) {
            data.put("last_price", last.getPrice());
            data.put("last_traded_at", last.getTradedAt());
        }
        data.put("buy_page", buyPage);
        data.put("change_7d_percent", windowedChangePercent(itemId, 7));
        data.put("change_30d_percent", windowedChangePercent(itemId, 30));

        Order.OrderType pageOrderType = buyPage ? Order.OrderType.BUY : Order.OrderType.SELL;
        List<Order> orders = new ArrayList<Order>(
            plugin.getOrderManager().getActiveOrders(itemId, pageOrderType)
        );
        if (buyPage) {
            orders.sort(Comparator.comparing(Order::getPrice).reversed().thenComparing(Order::getCreatedAt));
        } else {
            orders.sort(Comparator.comparing(Order::getPrice).thenComparing(Order::getCreatedAt));
        }
        List<MarketListingLayout.Slot> slots = MarketListingLayout.expand(orders, baseItem.getMaxStackSize());
        if (pageSize < 1 || pageSize > 45) {
            pageSize = 45;
        }
        int totalPages = MarketListingLayout.pageCount(slots, pageSize);
        int currentPage = Math.max(1, Math.min(page < 1 ? 1 : page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, slots.size());
        List<Map<String, Object>> listing = new ArrayList<Map<String, Object>>();
        for (int index = start; index < end; ++index) {
            MarketListingLayout.Slot slot = slots.get(index);
            Order order = slot.order();
            Map<String, Object> view = orderView(order);
            view.put("display_quantity", slot.amount());
            view.put("own", uuid != null && order.getPlayerUuid().equalsIgnoreCase(uuid));
            view.put("display_name", resolveOrderItemName(order, baseItem, item));
            listing.add(view);
        }
        data.put("listing", listing);
        data.put("page", currentPage);
        data.put("page_size", pageSize);
        data.put("total_pages", totalPages);
        data.put("total_orders", slots.size());

        boolean category = plugin.getItemManager().getSpecialCategory(item) != null;
        data.put("is_special_category", category);
        data.put("can_quick_sell", !buyPage && !category);
        data.put("can_supply", buyPage);
        data.put("can_place_buy", buyPage);
        if (buyPage && uuid != null && validUuid(uuid)) {
            SupplyPlanner.Plan plan = plugin.getOrderManager().webSupplyPlan(uuid, item);
            data.put("supply_plan", map(
                "available_quantity", plan.availableQuantity(),
                "matched_quantity", plan.matchedQuantity(),
                "gross_amount", plan.grossAmount(),
                "tax_amount", plan.taxAmount(plugin.getTaxRatePercent()),
                "expected_received", plan.grossAmount().subtract(plan.taxAmount(plugin.getTaxRatePercent()))
            ));
        }
        return ok(data);
    }

    /** 品种详情（默认出售模式，兼容网页简单场景）。 */
    public Map<String, Object> itemDetail(String uuid, int itemId, int page, int pageSize) {
        return this.itemDetail(uuid, itemId, false, page, pageSize);
    }

    /** 盘口：买盘（价高优先）与卖盘（价低优先），聚合档位 + 原始挂单。 */
    public Map<String, Object> orderBook(int itemId) {
        List<Order> buys = plugin.getOrderManager().getActiveOrders(itemId, Order.OrderType.BUY);
        List<Order> sells = plugin.getOrderManager().getActiveOrders(itemId, Order.OrderType.SELL);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("bids", aggregateLevels(buys, false));
        data.put("asks", aggregateLevels(sells, true));
        List<Map<String, Object>> bidViews = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> askViews = new ArrayList<Map<String, Object>>();
        for (Order order : buys) {
            bidViews.add(orderView(order));
        }
        for (Order order : sells) {
            askViews.add(orderView(order));
        }
        data.put("bids_raw", bidViews);
        data.put("asks_raw", askViews);
        Trade last = plugin.getTradeManager().getLastTrade(itemId);
        data.put("last_price", last == null ? null : last.getPrice());
        return ok(data);
    }

    /** 我的挂单。 */
    public Map<String, Object> myOrders(String uuid) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Order order : plugin.getOrderManager().getPlayerOrders(uuid)) {
            list.add(orderView(order));
        }
        return ok(map("orders", list));
    }

    /** 我的成交（分页）。 */
    public Map<String, Object> myTrades(String uuid, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
        List<Trade> trades = plugin.getStorageManager().getTradesByPlayer(uuid, size, (page - 1) * size);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Trade trade : trades) {
            list.add(tradeView(trade, uuid));
        }
        return ok(map("trades", list, "page", page, "size", size));
    }

    /** 我的交易记录页：与游戏内“我的交易记录”相同（挂单在前、成交在后，合并分页）。 */
    public Map<String, Object> myHistory(String uuid, int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 45;
        }
        List<Order> orders = plugin.getOrderManager().getPlayerOrders(uuid);
        List<Trade> trades = plugin.getStorageManager().getTradesByPlayer(uuid, 200, 0);
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Order order : orders) {
            Map<String, Object> view = orderView(order);
            view.put("entry_type", "order");
            view.put("item_name", resolveItemName(order.getItemId()));
            entries.add(view);
        }
        for (Trade trade : trades) {
            Map<String, Object> view = tradeView(trade, uuid);
            view.put("entry_type", "trade");
            view.put("item_name", resolveItemName(trade.getItemId()));
            entries.add(view);
        }
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int currentPage = Math.min(page, totalPages);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, entries.size());
        return ok(map(
            "entries", new ArrayList<Map<String, Object>>(entries.subList(start, end)),
            "page", currentPage,
            "page_size", pageSize,
            "total_pages", totalPages,
            "total_entries", entries.size()
        ));
    }

    /** 我的仓库（物品 + 货币）。 */
    public Map<String, Object> myWarehouse(String uuid) {
        Map<String, Integer> raw = plugin.getStorageManager().getPlayerItemWarehouse(uuid);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            ItemStack stack = ItemSerializer.itemFromBase64(entry.getKey());
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("item_base64", entry.getKey());
            view.put("quantity", entry.getValue());
            view.put("display_name", stack == null ? "\u672a\u77e5\u7269\u54c1" : ItemDisplayNames.resolve(stack));
            view.put("material", stack == null ? null : stack.getType().name());
            items.add(view);
        }
        return ok(map(
            "items", items,
            "money_balance", plugin.getStorageManager().getMoneyWarehouseBalance(uuid),
            "hint", "\u7f51\u9875\u4e0b\u5355\u4f7f\u7528\u4ed3\u5e93\u8d44\u91d1/\u7269\u54c1\uff1b\u63d0\u53d6\u5230\u6e38\u620f\u80cc\u5305\u9700\u8981\u73a9\u5bb6\u5728\u7ebf\u65f6\u8c03\u7528 warehouse_withdraw_*"
        ));
    }

    /** 市场信息（税率/货币/兑换比例/公告/涨跌停/限价等）。 */
    public Map<String, Object> marketInfo() {
        BigDecimal tax = TaxCalculator.tax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
        return ok(map(
            "currency_name", plugin.getCurrencyName(),
            "tax_rate_percent", plugin.getTaxRatePercent(),
            "diamond_to_money", plugin.getDiamondToMoneyAmount(),
            "diamond_exchange_tax", tax,
            "diamond_exchange_received", TaxCalculator.afterTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent()),
            "diamond_exchange_cost", TaxCalculator.withTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent()),
            "price_limit_enabled", plugin.isPriceLimitEnabled(),
            "limit_up_percent", plugin.getLimitUpPercent(),
            "limit_down_percent", plugin.getLimitDownPercent(),
            "max_order_quantity", plugin.getMaxOrderQuantity(),
            "price_tick", plugin.getPriceTick(),
            "min_price", plugin.getMinPrice(),
            "max_price", plugin.getMaxPrice(),
            "order_expire_days", plugin.getOrderExpireDays(),
            "announcements", plugin.getAnnouncements()
        ));
    }

    /** 公告列表（分页）。 */
    public Map<String, Object> announcements(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 45;
        }
        List<String> all = plugin.getAnnouncements();
        int totalPages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int currentPage = Math.min(page, totalPages);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = start; i < end; ++i) {
            list.add(map("id", i + 1, "content", all.get(i)));
        }
        return ok(map(
            "announcements", list,
            "page", currentPage,
            "page_size", pageSize,
            "total_pages", totalPages,
            "total", all.size()
        ));
    }

    /** 原版物品目录搜索（对应“搜索添加”输入框）。 */
    public Map<String, Object> catalogSearch(String query) {
        if (query == null || query.isBlank()) {
            return fail("\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\u3002");
        }
        com.github.exchange.util.ItemDatabase.ItemEntry entry = plugin.getItemDatabase().search(query.trim());
        if (entry == null) {
            return fail("\u672a\u627e\u5230\u5339\u914d\u7684\u7269\u54c1\uff1a" + query.trim());
        }
        return ok(map("name", entry.getName(), "id", entry.getId()));
    }

    /** 供货计划（对应详情页“一键供货”按钮显示的数据）。 */
    public Map<String, Object> supplyPlan(String uuid, int itemId) {
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        SupplyPlanner.Plan plan = plugin.getOrderManager().webSupplyPlan(uuid, item);
        BigDecimal tax = plan.taxAmount(plugin.getTaxRatePercent());
        return ok(map(
            "available_quantity", plan.availableQuantity(),
            "matched_quantity", plan.matchedQuantity(),
            "gross_amount", plan.grossAmount(),
            "tax_amount", tax,
            "expected_received", plan.grossAmount().subtract(tax),
            "allocations", allocationsView(plan.allocations())
        ));
    }

    /** 历史行情统计：每个品种最近 days 天的每日 OHLC + 成交量/额 + 销量排行。 */
    public Map<String, Object> getMarketStats(int days) {
        if (days < 1) {
            days = 30;
        }
        if (days > 365) {
            days = 365;
        }
        long cutoff = System.currentTimeMillis() - (long) days * 86400000L;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        Map<Integer, Map<String, DayStat>> byItem = new LinkedHashMap<Integer, Map<String, DayStat>>();
        Map<Integer, ItemStat> itemTotals = new LinkedHashMap<Integer, ItemStat>();
        for (ExchangeItem item : plugin.getItemManager().getAllItems()) {
            for (Trade trade : plugin.getStorageManager().getTradesByItem(item.getId(), 2000)) {
                if (trade.getTradedAt() == null || trade.getTradedAt().getTime() < cutoff) {
                    continue;
                }
                String date = fmt.format(trade.getTradedAt());
                Map<String, DayStat> dayMap = byItem.get(item.getId());
                if (dayMap == null) {
                    dayMap = new LinkedHashMap<String, DayStat>();
                    byItem.put(item.getId(), dayMap);
                }
                DayStat day = dayMap.get(date);
                if (day == null) {
                    day = new DayStat();
                    dayMap.put(date, day);
                }
                day.volume += trade.getQuantity();
                day.amount = day.amount.add(trade.getTotalAmount());
                BigDecimal p = trade.getPrice();
                if (day.open == null) {
                    day.open = p;
                }
                day.high = day.high == null ? p : day.high.max(p);
                day.low = day.low == null ? p : day.low.min(p);
                day.close = p;

                ItemStat stat = itemTotals.get(item.getId());
                if (stat == null) {
                    stat = new ItemStat();
                    itemTotals.put(item.getId(), stat);
                }
                stat.volume += trade.getQuantity();
                stat.amount = stat.amount.add(trade.getTotalAmount());
                stat.lastPrice = p;
            }
        }
        Map<String, Object> items = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> ranking = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Integer, ItemStat> entry : itemTotals.entrySet()) {
            int itemId = entry.getKey();
            ItemStat stat = entry.getValue();
            String name = resolveItemName(itemId);
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("item_id", itemId);
            view.put("item_name", name);
            view.put("last_close", stat.lastPrice);
            view.put("total_volume", stat.volume);
            view.put("total_amount", stat.amount);
            List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
            Map<String, DayStat> dayMap = byItem.get(itemId);
            if (dayMap != null) {
                for (Map.Entry<String, DayStat> dayEntry : dayMap.entrySet()) {
                    DayStat d = dayEntry.getValue();
                    history.add(map(
                        "date", dayEntry.getKey(),
                        "open", d.open,
                        "high", d.high,
                        "low", d.low,
                        "close", d.close,
                        "volume", d.volume
                    ));
                }
            }
            view.put("history", history);
            items.put(String.valueOf(itemId), view);
            ranking.add(map(
                "item_id", itemId,
                "item_name", name,
                "volume", stat.volume,
                "amount", stat.amount
            ));
        }
        ranking.sort((a, b) -> Integer.compare(
            ((Number) b.get("volume")).intValue(),
            ((Number) a.get("volume")).intValue()
        ));
        int totalVolume = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ItemStat stat : itemTotals.values()) {
            totalVolume += stat.volume;
            totalAmount = totalAmount.add(stat.amount);
        }
        return ok(map(
            "days", days,
            "items", items,
            "ranking", ranking,
            "total_volume", totalVolume,
            "total_amount", totalAmount,
            "total_items", itemTotals.size()
        ));
    }

    // ===================== 写操作（玩家锁 + 主线程串行） =====================

    /** 网页挂买单：从货币仓库扣款（含交易税，与游戏内一致），未成交部分退回仓库。 */
    public Map<String, Object> placeBuy(final String uuid, final int itemId, final BigDecimal price, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                String result = plugin.getOrderManager().webPlaceBuy(
                    uuid, resolveName(uuid), item, price, quantity
                );
                return resultOf(result, map("item_id", itemId, "quantity", quantity));
            }
        });
    }

    /** 网页挂卖单：从个人物品仓库扣货入托管。 */
    public Map<String, Object> placeSell(final String uuid, final int itemId, final BigDecimal price, final int quantity) {
        return this.placeSell(uuid, itemId, price, quantity, null);
    }

    /**
     * 网页挂卖单（指定具体物品）：对应游戏内“上架菜单”选择具体物品后统一上架。
     * 特殊类别品种必须携带具体物品 base64。
     */
    public Map<String, Object> placeSell(
        final String uuid, final int itemId, final BigDecimal price, final int quantity, final String itemBase64
    ) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                ItemStack actualItem = null;
                if (itemBase64 != null && !itemBase64.isEmpty()) {
                    actualItem = ItemSerializer.itemFromBase64(itemBase64);
                    if (actualItem == null) {
                        return fail("无效的物品数据（base64 无法解析）。");
                    }
                }
                String result = plugin.getOrderManager().webPlaceSell(
                    uuid, resolveName(uuid), item, actualItem, price, quantity
                );
                return resultOf(result, map(
                    "item_id", itemId,
                    "quantity", quantity,
                    "item_base64", itemBase64 == null ? "" : itemBase64
                ));
            }
        });
    }

    /** 网页撤单：退回仓库（admin=true 可撤他人订单）。 */
    public Map<String, Object> cancel(final String uuid, final int orderId, final boolean admin) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                String result = plugin.getOrderManager().webCancel(uuid, admin, orderId);
                return resultOf(result, map("order_id", orderId));
            }
        });
    }

    /** 市价买入：按当前最优卖价（最低卖价）从仓库资金买入。 */
    public Map<String, Object> marketBuy(final String uuid, final int itemId, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
                if (status != null && status.isSuspended()) {
                    return fail("该品种已停牌，无法市价交易。");
                }
                List<Order> sells = plugin.getOrderManager().getActiveOrders(itemId, Order.OrderType.SELL);
                if (sells.isEmpty()) {
                    return fail("当前没有可用卖单，无法买入。");
                }
                long available = 0L;
                BigDecimal topPrice = BigDecimal.ZERO;
                for (Order sellOrder : sells) {
                    available += sellOrder.getRemainingQty();
                    topPrice = sellOrder.getPrice();
                    if (available >= quantity) {
                        break;
                    }
                }
                if (available < quantity) {
                    return fail("当前市场存货不足，可用: " + available + " 个。");
                }
                String result = plugin.getOrderManager().webPlaceBuy(
                    uuid, resolveName(uuid), item, topPrice, quantity
                );
                Map<String, Object> data = map("item_id", itemId, "quantity", quantity, "price", topPrice);
                return result.startsWith("\u00a7c")
                    ? fail(result)
                    : ok(merge(data, map("message", "\u5df2\u6309\u5f53\u524d\u5e02\u573a\u4ef7\u63d0\u4ea4\u8d2d\u5165\u3002 " + result)));
            }
        });
    }

    /** 市价卖出：按最新成交价从仓库物品卖出。 */
    public Map<String, Object> marketSell(final String uuid, final int itemId, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
                if (status != null && status.isSuspended()) {
                    return fail("该品种已停牌，无法市价交易。");
                }
                BigDecimal marketPrice = status == null ? BigDecimal.ZERO : status.getLastClose();
                if (marketPrice == null || marketPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    return fail("当前无最新成交价，无法按市价下单。");
                }
                String result = plugin.getOrderManager().webPlaceSell(
                    uuid, resolveName(uuid), item, marketPrice, quantity
                );
                Map<String, Object> data = map("item_id", itemId, "quantity", quantity, "price", marketPrice);
                return result.startsWith("\u00a7c")
                    ? fail(result)
                    : ok(merge(data, map("message", "\u5df2\u6309\u5e02\u4ef7(" + marketPrice + ")\u63d0\u4ea4\u5356\u5355\u3002" + result)));
            }
        });
    }

    /** 点击卖单直接买入（对应详情页卖单格：左键 1 / Shift 整格 / 批量模式）。 */
    public Map<String, Object> directBuy(final String uuid, final int sellOrderId, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                String result = plugin.getOrderManager().webDirectBuy(
                    uuid, resolveName(uuid), sellOrderId, quantity
                );
                return resultOf(result, map("sell_order_id", sellOrderId, "quantity", quantity));
            }
        });
    }

    /** 点击求购单直接供货（对应详情页求购单格：左键 1 / Shift 整格）。 */
    public Map<String, Object> directSell(final String uuid, final int buyOrderId, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                String result = plugin.getOrderManager().webDirectSell(
                    uuid, resolveName(uuid), buyOrderId, quantity
                );
                return resultOf(result, map("buy_order_id", buyOrderId, "quantity", quantity));
            }
        });
    }

    /** 快速上架：按最低卖价/最新成交价上架仓库中全部同类物品。 */
    public Map<String, Object> quickSell(final String uuid, final int itemId) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                String result = plugin.getOrderManager().webQuickSell(uuid, resolveName(uuid), item);
                return resultOf(result, map("item_id", itemId));
            }
        });
    }

    /** 一键供货：把仓库中可匹配其他玩家求购单的物品全部供货。 */
    public Map<String, Object> supplyAll(final String uuid, final int itemId) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                String result = plugin.getOrderManager().webSupplyAll(uuid, resolveName(uuid), item);
                return resultOf(result, map("item_id", itemId));
            }
        });
    }

    /** 部分取回：左键取 1 等价 quantity=1；Shift 取整格等价 quantity=显示数量。 */
    public Map<String, Object> withdrawOrderQuantity(
        final String uuid, final int orderId, final int quantity, final boolean admin
    ) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                String result = plugin.getOrderManager().webWithdrawQuantity(
                    uuid, admin, orderId, quantity
                );
                return resultOf(result, map("order_id", orderId, "quantity", quantity));
            }
        });
    }

    /** 注册商品到市场目录（对应“搜索添加/放入输入槽”流程；不消耗物品，admin=true 跳过每日上限）。 */
    public Map<String, Object> registerCatalogItem(final String uuid, final String itemBase64, final boolean admin) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                ItemStack item = ItemSerializer.itemFromBase64(itemBase64);
                if (item == null || item.getType().isAir()) {
                    return fail("无效的物品数据（base64 无法解析）。");
                }
                ItemManager.RegisterResult result = plugin.getItemManager().registerCatalogItem(
                    uuid, resolveName(uuid), item, admin
                );
                Map<String, Object> data = map(
                    "success", result.isSuccess(),
                    "newly_registered", result.isNewlyRegistered(),
                    "item_id", result.getItem() == null ? null : result.getItem().getId()
                );
                return result.isSuccess() ? ok(data) : fail(result.getMessage());
            }
        });
    }

    /** 网页货币兑换：仓库钻石 -> 余额星光点（与游戏内税率一致）。 */
    public Map<String, Object> exchangeDiamondForMoney(final String uuid) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                if (plugin.isGrowthAccessRestricted(uuid)) {
                    return fail(plugin.growthAccessMessage(uuid));
                }
                BigDecimal tax = TaxCalculator.tax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
                BigDecimal received = TaxCalculator.afterTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
                String diamondBase64 = findWarehouseDiamond(uuid);
                if (diamondBase64 == null) {
                    return fail("仓库中没有可用于兑换的钻石。");
                }
                Integer qty = plugin.getStorageManager().getPlayerItemWarehouse(uuid).get(diamondBase64);
                if (qty == null || qty <= 0) {
                    return fail("仓库中没有可用于兑换的钻石。");
                }
                if (!plugin.getStorageManager().takeFromPlayerItemWarehouse(uuid, diamondBase64, 1)) {
                    return fail("钻石扣减失败，请重试。");
                }
                if (received.compareTo(BigDecimal.ZERO) > 0 && !EconomyUtil.deposit(parseUuid(uuid), received)) {
                    plugin.getStorageManager().addToPlayerItemWarehouse(uuid, diamondBase64, 1);
                    return fail("入账失败，钻石已退回仓库。");
                }
                plugin.collectTax(tax);
                return ok(map(
                    "received", received,
                    "tax", tax,
                    "rate_percent", plugin.getTaxRatePercent(),
                    "message", "\u5151\u6362\u6210\u529f\uff1a1 \u94bb\u77f3 -> " + received.toPlainString()
                        + " " + plugin.getCurrencyName() + "\uff08\u7a0e\u989d " + tax.toPlainString() + "\uff09"
                ));
            }
        });
    }

    /** 网页货币兑换：余额星光点 -> 仓库钻石（与游戏内税率一致）。 */
    public Map<String, Object> exchangeMoneyForDiamond(final String uuid) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                if (plugin.isGrowthAccessRestricted(uuid)) {
                    return fail(plugin.growthAccessMessage(uuid));
                }
                BigDecimal tax = TaxCalculator.tax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
                BigDecimal totalCost = TaxCalculator.withTax(plugin.getDiamondToMoneyAmount(), plugin.getTaxRatePercent());
                if (!EconomyUtil.hasBalance(parseUuid(uuid), totalCost)) {
                    return fail("余额不足，需要 " + totalCost.toPlainString() + " " + plugin.getCurrencyName()
                        + "（含税 " + tax.toPlainString() + "）。");
                }
                if (!EconomyUtil.withdraw(parseUuid(uuid), totalCost)) {
                    return fail("扣款失败，请稍后重试。");
                }
                ItemStack diamond = new ItemStack(plugin.getDiamondMaterial(), 1);
                String diamondBase64 = ItemSerializer.itemToBase64(diamond);
                if (diamondBase64 == null
                    || !plugin.getStorageManager().addToPlayerItemWarehouse(uuid, diamondBase64, 1)) {
                    EconomyUtil.deposit(parseUuid(uuid), totalCost);
                    return fail("钻石仓库保存失败，星光点已退回。");
                }
                plugin.collectTax(tax);
                return ok(map(
                    "cost", totalCost,
                    "tax", tax,
                    "rate_percent", plugin.getTaxRatePercent(),
                    "message", "\u5151\u6362\u6210\u529f\uff1a" + totalCost.toPlainString() + " " + plugin.getCurrencyName()
                        + " -> 1 \u94bb\u77f3\uff08\u7a0e\u989d " + tax.toPlainString() + "\uff09"
                ));
            }
        });
    }

    /** 余额 -> 货币仓库（对应游戏内 /se depositmoney）。 */
    public Map<String, Object> depositMoney(final String uuid, final BigDecimal amount) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    return fail("金额必须大于 0");
                }
                if (!EconomyUtil.hasBalance(parseUuid(uuid), amount)) {
                    return fail("余额不足");
                }
                if (!EconomyUtil.withdraw(parseUuid(uuid), amount)) {
                    return fail("扣款失败，请重试");
                }
                if (!plugin.getStorageManager().addToMoneyWarehouse(uuid, amount)) {
                    EconomyUtil.deposit(parseUuid(uuid), amount);
                    return fail("仓库入账失败，款项已退回");
                }
                return ok(map("deposited", amount));
            }
        });
    }

    /** 货币仓库 -> 余额（对应游戏内 /se withdrawmoney）。 */
    public Map<String, Object> withdrawMoney(final String uuid, final BigDecimal amount) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    return fail("金额必须大于 0");
                }
                if (plugin.getStorageManager().getMoneyWarehouseBalance(uuid).compareTo(amount) < 0) {
                    return fail("货币仓库余额不足");
                }
                if (!plugin.getStorageManager().takeFromMoneyWarehouse(uuid, amount)) {
                    return fail("仓库扣款失败，请重试");
                }
                if (!EconomyUtil.deposit(parseUuid(uuid), amount)) {
                    plugin.getStorageManager().addToMoneyWarehouse(uuid, amount);
                    return fail("入账失败，款项已退回仓库");
                }
                return ok(map("withdrawn", amount));
            }
        });
    }

    /** 手持物品 -> 个人物品仓库（对应游戏内 /se deposit；需要玩家在线）。 */
    public Map<String, Object> depositHandItem(final String uuid, final int quantity) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                Player player = Bukkit.getPlayer(parseUuid(uuid));
                if (player == null || !player.isOnline()) {
                    return fail("该操作需要玩家在线（手持物品存入仓库）。");
                }
                if (quantity <= 0) {
                    return fail("数量必须大于 0");
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
                    return fail("请手持要存入仓库的物品");
                }
                if (hand.getAmount() < quantity) {
                    return fail("手中数量不足");
                }
                String base64 = ItemSerializer.itemToBase64(hand);
                if (base64 == null) {
                    return fail("物品序列化失败");
                }
                if (!plugin.getStorageManager().addToPlayerItemWarehouse(uuid, base64, quantity)) {
                    return fail("仓库入账失败，物品已退回");
                }
                hand.setAmount(hand.getAmount() - quantity);
                player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
                player.updateInventory();
                return ok(map("deposited", quantity));
            }
        });
    }

    /** 一键提取仓库（物品 + 星光点到游戏背包/余额；对应仓库页“一键提取”按钮，需要玩家在线）。 */
    public Map<String, Object> warehouseWithdrawAll(final String uuid) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                Player player = Bukkit.getPlayer(parseUuid(uuid));
                if (player == null || !player.isOnline()) {
                    return fail("该操作需要玩家在线（提取到游戏背包）。");
                }
                plugin.getStorageManager().withdrawWarehouse(player);
                return ok(map("message", "\u5df2\u5728\u6e38\u620f\u5185\u6267\u884c\u4e00\u952e\u63d0\u53d6"));
            }
        });
    }

    /** 提取仓库星光点到余额（对应仓库页“仓库星光点”按钮，需要玩家在线）。 */
    public Map<String, Object> warehouseWithdrawMoney(final String uuid) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                Player player = Bukkit.getPlayer(parseUuid(uuid));
                if (player == null || !player.isOnline()) {
                    return fail("该操作需要玩家在线（提取到余额）。");
                }
                plugin.getStorageManager().withdrawWarehouseMoney(player);
                return ok(map("message", "\u5df2\u5728\u6e38\u620f\u5185\u6267\u884c\u63d0\u53d6\u661f\u5149\u70b9"));
            }
        });
    }

    /** 提取指定仓库物品到背包（对应仓库页点击物品，需要玩家在线）。 */
    public Map<String, Object> warehouseWithdrawItem(final String uuid, final String itemBase64) {
        return withPlayerLock(uuid, new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!validUuid(uuid)) {
                    return fail("无效的玩家 UUID");
                }
                Player player = Bukkit.getPlayer(parseUuid(uuid));
                if (player == null || !player.isOnline()) {
                    return fail("该操作需要玩家在线（提取到背包）。");
                }
                if (itemBase64 == null || itemBase64.isEmpty()) {
                    return fail("无效的仓库物品");
                }
                plugin.getStorageManager().withdrawWarehouseItem(player, itemBase64);
                return ok(map("message", "\u5df2\u5728\u6e38\u620f\u5185\u6267\u884c\u63d0\u53d6\u7269\u54c1"));
            }
        });
    }

    // ===================== 管理员接口 =====================

    /** 管理员：停牌/复牌。 */
    public Map<String, Object> adminSuspend(int itemId, boolean suspend) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
                if (status == null) {
                    return fail("品种不存在");
                }
                status.setSuspended(suspend);
                plugin.getItemManager().updateItemStatus(status);
                return ok(map("suspended", suspend, "item_id", itemId));
            }
        });
    }

    /** 管理员：设置税率（0-100）。 */
    public Map<String, Object> adminSetTax(BigDecimal percent) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if (!plugin.setTaxRatePercent(percent)) {
                    return fail("税率必须在 0-100 之间");
                }
                return ok(map("tax_rate_percent", plugin.getTaxRatePercent()));
            }
        });
    }

    /** 管理员：公告管理 add / edit / delete / addline / delline。 */
    public Map<String, Object> adminAnnouncement(String action, int id, String content) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                if ("add".equalsIgnoreCase(action)) {
                    if (content == null || content.isBlank()) {
                        return fail("公告内容不能为空");
                    }
                    return ok(map("announcement_id", plugin.addAnnouncement(content)));
                }
                if ("edit".equalsIgnoreCase(action)) {
                    return plugin.editAnnouncement(id, content) ? ok(map("edited", true)) : fail("公告不存在");
                }
                if ("delete".equalsIgnoreCase(action)) {
                    return plugin.deleteAnnouncement(id) ? ok(map("deleted", true)) : fail("公告不存在");
                }
                if ("addline".equalsIgnoreCase(action)) {
                    List<String> anns = plugin.getAnnouncements();
                    if (id <= 0 || id > anns.size()) {
                        return fail("公告ID不存在: " + id);
                    }
                    if (content == null || content.isBlank()) {
                        return fail("内容不能为空");
                    }
                    plugin.editAnnouncement(id, anns.get(id - 1) + "\n" + content);
                    return ok(map("edited", true));
                }
                if ("delline".equalsIgnoreCase(action) || "popline".equalsIgnoreCase(action)) {
                    List<String> anns = plugin.getAnnouncements();
                    if (id <= 0 || id > anns.size()) {
                        return fail("公告ID不存在: " + id);
                    }
                    String[] lines = anns.get(id - 1).split("\\n");
                    if (lines.length <= 1) {
                        return fail("该公告只有一行，无法再删除最下方一行。");
                    }
                    StringBuilder builder = new StringBuilder();
                    for (int i = 0; i < lines.length - 1; ++i) {
                        if (i > 0) {
                            builder.append("\n");
                        }
                        builder.append(lines[i]);
                    }
                    plugin.editAnnouncement(id, builder.toString());
                    return ok(map("edited", true));
                }
                return fail("不支持的公告操作: " + action);
            }
        });
    }

    /** 管理员：重载配置并重连数据库。 */
    public Map<String, Object> adminReload() {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                plugin.loadConfigValues();
                boolean ok = plugin.reconnectStorage();
                return ok(map("reconnected", ok));
            }
        });
    }

    /** 管理员：重连数据库。 */
    public Map<String, Object> adminReconnectDb() {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                return ok(map("reconnected", plugin.reconnectStorage()));
            }
        });
    }

    // ===================== 并发与调度 =====================

    private Map<String, Object> withPlayerLock(String uuid, Callable<Map<String, Object>> task) {
        if (uuid == null || uuid.isBlank()) {
            return fail("无效的玩家 UUID");
        }
        ReentrantLock lock = playerLocks.computeIfAbsent(uuid, key -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(15L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("操作被中断，请重试");
        }
        if (!acquired) {
            return fail("你有一笔交易操作正在进行，请稍后再试");
        }
        try {
            return onMain(task);
        } finally {
            lock.unlock();
            if (!lock.isLocked()) {
                playerLocks.remove(uuid, lock);
            }
        }
    }

    private Map<String, Object> onMain(Callable<Map<String, Object>> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                plugin.getLogger().warning("[WebMarket] 操作异常: " + e.getMessage());
                return fail("操作失败: " + e.getMessage());
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(30L, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("[WebMarket] 主线程执行失败: " + e.getMessage());
            return fail("服务器繁忙，请稍后再试");
        }
    }

    // ===================== 视图转换与工具 =====================

    private Map<String, Object> itemView(ExchangeItem item, boolean buyPage) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", item.getId());
        view.put("material", item.getMaterial());
        view.put("display_name", item.getDisplayName());
        view.put("item_name", item.getItemName());
        view.put("item_lore", item.getItemLore());
        view.put("created_by", item.getCreatedByName());
        view.put("created_at", item.getCreatedAt());
        ItemStatus status = plugin.getItemManager().getItemStatus(item.getId());
        if (status != null) {
            view.put("last_close", status.getLastClose());
            view.put("last_open", status.getLastOpen());
            view.put("high_today", status.getHighToday());
            view.put("low_today", status.getLowToday());
            view.put("volume_today", status.getVolumeToday());
            view.put("suspended", status.isSuspended());
            view.put("lowest_sell", status.getLowestSellCurrent());
        }
        SpecialCategory category = plugin.getItemManager().getSpecialCategory(item);
        view.put("special_category", category == null ? null : category.displayName());
        view.put("lowest_sell_price", plugin.getOrderManager().getLowestSellPrice(item.getId()));
        view.put("highest_buy_price", plugin.getOrderManager().getHighestBuyPrice(item.getId()));
        long active = MarketPageFilter.activeRemainingQuantity(
            plugin.getOrderManager().getActiveOrders(
                item.getId(),
                buyPage ? Order.OrderType.BUY : Order.OrderType.SELL
            )
        );
        view.put("active_stock", active);
        view.put("change_7d_percent", windowedChangePercent(item.getId(), 7));
        view.put("change_30d_percent", windowedChangePercent(item.getId(), 30));
        return view;
    }

    private Map<String, Object> statusView(ItemStatus status) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (status == null) {
            return view;
        }
        view.put("item_id", status.getItemId());
        view.put("suspended", status.isSuspended());
        view.put("last_close", status.getLastClose());
        view.put("last_open", status.getLastOpen());
        view.put("high_today", status.getHighToday());
        view.put("low_today", status.getLowToday());
        view.put("volume_today", status.getVolumeToday());
        view.put("lowest_sell", status.getLowestSellCurrent());
        return view;
    }

    private Map<String, Object> orderView(Order order) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", order.getId());
        view.put("order_type", order.getOrderType().name());
        view.put("item_id", order.getItemId());
        view.put("player_uuid", order.getPlayerUuid());
        view.put("player_name", order.getPlayerName());
        view.put("price", order.getPrice());
        view.put("quantity", order.getQuantity());
        view.put("filled_qty", order.getFilledQty());
        view.put("remaining_qty", order.getRemainingQty());
        view.put("status", order.getStatus().name());
        view.put("created_at", order.getCreatedAt());
        view.put("item_name", resolveItemName(order.getItemId()));
        return view;
    }

    private Map<String, Object> tradeView(Trade trade, String viewerUuid) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", trade.getId());
        view.put("item_id", trade.getItemId());
        view.put("buyer_uuid", trade.getBuyerUuid());
        view.put("seller_uuid", trade.getSellerUuid());
        view.put("price", trade.getPrice());
        view.put("quantity", trade.getQuantity());
        view.put("total_amount", trade.getTotalAmount());
        view.put("buyer_fee", trade.getBuyerFee());
        view.put("seller_fee", trade.getSellerFee());
        view.put("traded_at", trade.getTradedAt());
        view.put("buy_order_id", trade.getBuyOrderId());
        view.put("sell_order_id", trade.getSellOrderId());
        if (viewerUuid != null) {
            boolean isBuy = viewerUuid.equalsIgnoreCase(trade.getBuyerUuid());
            view.put("role", isBuy ? "BUYER" : "SELLER");
            view.put("fee", isBuy ? trade.getBuyerFee() : trade.getSellerFee());
        }
        view.put("item_name", resolveItemName(trade.getItemId()));
        return view;
    }

    private List<Map<String, Object>> allocationsView(List<SupplyPlanner.Allocation> allocations) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        if (allocations == null) {
            return list;
        }
        for (SupplyPlanner.Allocation allocation : allocations) {
            list.add(map(
                "buy_order_id", allocation.order().getId(),
                "price", allocation.order().getPrice(),
                "quantity", allocation.quantity()
            ));
        }
        return list;
    }

    /** 聚合盘口：按价格分组汇总剩余量，最多 5 档。 */
    private List<Map<String, Object>> aggregateLevels(List<Order> orders, boolean ascending) {
        Map<String, BigDecimal> levelQty = new LinkedHashMap<String, BigDecimal>();
        for (Order order : orders) {
            BigDecimal existing = levelQty.get(order.getPrice().toPlainString());
            levelQty.put(
                order.getPrice().toPlainString(),
                existing == null
                    ? BigDecimal.valueOf(order.getRemainingQty())
                    : existing.add(BigDecimal.valueOf(order.getRemainingQty()))
            );
        }
        List<Map<String, Object>> levels = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, BigDecimal> entry : levelQty.entrySet()) {
            levels.add(map("price", new BigDecimal(entry.getKey()), "quantity", entry.getValue()));
        }
        levels.sort((a, b) -> ascending
            ? ((BigDecimal) a.get("price")).compareTo((BigDecimal) b.get("price"))
            : ((BigDecimal) b.get("price")).compareTo((BigDecimal) a.get("price")));
        return levels.size() > 5 ? new ArrayList<Map<String, Object>>(levels.subList(0, 5)) : levels;
    }

    private BigDecimal windowedChangePercent(int itemId, int days) {
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
        return currentLowest.subtract(referenceLowest)
            .divide(referenceLowest, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100L))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean matchesCatalogSearch(ExchangeItem item, String query) {
        ItemStack baseItem = ItemSerializer.itemFromBase64(item.getItemBase64());
        String typeName = baseItem == null ? item.getMaterial() : baseItem.getType().name();
        return MarketListingSearch.matches(
            query,
            item.getId(),
            item.getDisplayName(),
            item.getItemName(),
            item.getMaterial(),
            item.getItemName(),
            typeName
        );
    }

    private String resolveOrderItemName(Order order, ItemStack baseItem, ExchangeItem item) {
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            ItemStack orderBase = escrow == null ? null : ItemSerializer.itemFromBase64(escrow.getItemBase64());
            if (orderBase != null) {
                return ItemDisplayNames.resolve(orderBase);
            }
        }
        return baseItem == null ? item.getDisplayName() : ItemDisplayNames.resolve(baseItem);
    }

    private String resolveItemName(int itemId) {
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return "#" + itemId;
        }
        return item.getDisplayName() != null ? item.getDisplayName() : item.getItemName();
    }

    private String findWarehouseDiamond(String uuid) {
        for (Map.Entry<String, Integer> entry : plugin.getStorageManager().getPlayerItemWarehouse(uuid).entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            ItemStack stack = ItemSerializer.itemFromBase64(entry.getKey());
            if (stack != null && stack.getType() == plugin.getDiamondMaterial()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String resolveName(String uuid) {
        try {
            org.bukkit.OfflinePlayer player = Bukkit.getOfflinePlayer(parseUuid(uuid));
            if (player != null && player.getName() != null && !player.getName().isBlank()) {
                return player.getName();
            }
        } catch (Throwable ignored) {
        }
        return uuid.length() > 8 ? "玩家#" + uuid.substring(0, 8) : "玩家#" + uuid;
    }

    private boolean validUuid(String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private UUID parseUuid(String uuid) {
        return UUID.fromString(uuid);
    }

    private Map<String, Object> resultOf(String result, Map<String, Object> data) {
        if (result == null || result.startsWith("\u00a7c")) {
            return fail(result == null ? "操作失败" : result);
        }
        data.put("message", result);
        return ok(data);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("data", data == null ? new LinkedHashMap<String, Object>() : data);
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(first);
        if (second != null) {
            merged.putAll(second);
        }
        return merged;
    }

    private static final class DayStat {
        int volume;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
    }

    private static final class ItemStat {
        int volume;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal lastPrice;
    }
}
