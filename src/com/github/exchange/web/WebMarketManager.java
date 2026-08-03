package com.github.exchange.web;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.TaxCalculator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 网页端市场导出 API（WebMarketManager）。
 *
 * <p>设计要点（防刷/一致性）：
 * 单一写入者：所有会移动资产的操作只在插件主线程串行执行（callSyncMethod + 内部同步）；
 * 网页资金/物品走「仓库通道」：买单从货币仓库扣款、卖单从个人物品仓库扣货，
 * 网页操作永不触碰在线背包；游戏内操作永不触碰仓库（除显式存取）；
 * 下单/撤单/撮合复用插件既有存储与托管模型，成交与撤单先落库、再响应。</p>
 */
public class WebMarketManager {

    private final StockExchangePlugin plugin;

    public WebMarketManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    // ===================== 只读查询（存储层自带同步） =====================

    /** 全部上市品种（含行情与停牌状态） */
    public Map<String, Object> listItems() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ExchangeItem item : plugin.getItemManager().getAllItems()) {
            items.add(itemView(item));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("items", items);
        data.put("currency_name", plugin.getCurrencyName());
        data.put("tax_rate_percent", plugin.getTaxRatePercent());
        return ok(data);
    }

    /** 品种详情 */
    public Map<String, Object> itemDetail(int itemId) {
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("item", itemView(item));
        data.put("status", statusView(plugin.getItemManager().getItemStatus(itemId)));
        Trade last = plugin.getStorageManager().getLastTrade(itemId);
        if (last != null) {
            data.put("last_price", last.getPrice());
            data.put("last_traded_at", last.getTradedAt());
        }
        return ok(data);
    }

    /** 盘口：买盘（价高优先）与卖盘（价低优先） */
    public Map<String, Object> orderBook(int itemId) {
        List<Order> buys = plugin.getStorageManager().getActiveOrdersByItem(itemId, Order.OrderType.BUY);
        List<Order> sells = plugin.getStorageManager().getActiveOrdersByItem(itemId, Order.OrderType.SELL);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("bids", aggregateLevels(buys, false));
        data.put("asks", aggregateLevels(sells, true));
        Trade last = plugin.getStorageManager().getLastTrade(itemId);
        data.put("last_price", last == null ? null : last.getPrice());
        return ok(data);
    }

    /** 我的挂单 */
    public Map<String, Object> myOrders(String uuid) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Order order : plugin.getStorageManager().getOrdersByPlayer(uuid)) {
            list.add(orderView(order));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("orders", list);
        return ok(data);
    }

    /** 我的成交（分页） */
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
            list.add(tradeView(trade));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("trades", list);
        data.put("page", page);
        data.put("size", size);
        return ok(data);
    }

    /** 我的仓库（物品 + 货币） */
    public Map<String, Object> myWarehouse(String uuid) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("items", plugin.getStorageManager().getPlayerItemWarehouse(uuid));
        data.put("money_balance", plugin.getStorageManager().getMoneyWarehouseBalance(uuid));
        data.put("hint", "网页下单使用仓库资金/物品；提取请在游戏内执行 /se withdraw 与 /se withdrawmoney");
        return ok(data);
    }

    /** 市场信息（税率/货币/兑换比例/公告/涨跌停） */
    public Map<String, Object> marketInfo() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("currency_name", plugin.getCurrencyName());
        data.put("tax_rate_percent", plugin.getTaxRatePercent());
        data.put("diamond_to_money", plugin.getDiamondToMoneyAmount());
        data.put("price_limit_enabled", plugin.isPriceLimitEnabled());
        data.put("limit_up_percent", plugin.getLimitUpPercent());
        data.put("limit_down_percent", plugin.getLimitDownPercent());
        data.put("max_order_quantity", plugin.getMaxOrderQuantity());
        data.put("price_tick", plugin.getPriceTick());
        data.put("announcements", plugin.getAnnouncements());
        return ok(data);
    }
    // ===================== 写操作（强制主线程串行） =====================

    /** 网页挂买单：从货币仓库扣款入托管 */
    public Map<String, Object> placeBuy(final String uuid, final int itemId, final BigDecimal price, final int quantity) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                return placeBuyInternal(uuid, itemId, price, quantity);
            }
        });
    }

    /** 网页挂卖单：从个人物品仓库扣货入托管 */
    public Map<String, Object> placeSell(final String uuid, final int itemId, final BigDecimal price, final int quantity) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                return placeSellInternal(uuid, itemId, price, quantity);
            }
        });
    }

    /** 网页撤单：退回仓库 */
    public Map<String, Object> cancel(final String uuid, final int orderId, final boolean admin) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                return cancelInternal(uuid, orderId, admin);
            }
        });
    }

    /** 市价买入 */
    public Map<String, Object> marketBuy(final String uuid, final int itemId, final int quantity) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                List<Order> sells = plugin.getStorageManager().getActiveOrdersByItem(itemId, Order.OrderType.SELL);
                if (sells.isEmpty()) {
                    return fail("当前没有可用卖单");
                }
                return placeBuyInternal(uuid, itemId, sells.get(0).getPrice(), quantity);
            }
        });
    }

    /** 市价卖出 */
    public Map<String, Object> marketSell(final String uuid, final int itemId, final int quantity) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
                BigDecimal price = status == null ? BigDecimal.ZERO : status.getLastClose();
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                    return fail("当前无最新成交价，无法按市价下单");
                }
                return placeSellInternal(uuid, itemId, price, quantity);
            }
        });
    }

    /** 一键卖出仓库全部 */
    public Map<String, Object> quickSell(final String uuid, final int itemId) {
        return onMain(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                ExchangeItem item = plugin.getItemManager().getItem(itemId);
                if (item == null) {
                    return fail("品种不存在");
                }
                Map<String, Integer> warehouse = plugin.getStorageManager().getPlayerItemWarehouse(uuid);
                Integer qty = warehouse.get(item.getItemBase64());
                if (qty == null || qty <= 0) {
                    return fail("个人物品仓库中没有该品种（请先 /se deposit）");
                }
                BigDecimal lowest = lowestSellPrice(itemId);
                if (lowest == null || lowest.compareTo(BigDecimal.ZERO) <= 0) {
                    ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
                    if (status != null && status.getLastClose() != null && status.getLastClose().compareTo(BigDecimal.ZERO) > 0) {
                        lowest = status.getLastClose();
                    }
                }
                if (lowest == null || lowest.compareTo(BigDecimal.ZERO) <= 0) {
                    return fail("当前无参考价格，请手动输入价格");
                }
                return placeSellInternal(uuid, itemId, lowest, Math.min(qty.intValue(), plugin.getMaxOrderQuantity()));
            }
        });
    }

    /** 管理员：停牌/复牌 */
    public Map<String, Object> adminSuspend(int itemId, boolean suspend) {
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            return fail("品种不存在");
        }
        status.setSuspended(suspend);
        plugin.getItemManager().updateItemStatus(status);
        return ok(map("suspended", suspend, "item_id", itemId));
    }

    /** 管理员：设置税率（0-100） */
    public Map<String, Object> adminSetTax(BigDecimal percent) {
        if (!plugin.setTaxRatePercent(percent)) {
            return fail("税率必须在 0-100 之间");
        }
        return ok(map("tax_rate_percent", plugin.getTaxRatePercent()));
    }

    /** 管理员：公告管理 add / edit / delete */
    public Map<String, Object> adminAnnouncement(String action, int id, String content) {
        if ("add".equalsIgnoreCase(action)) {
            return ok(map("announcement_id", plugin.addAnnouncement(content)));
        }
        if ("edit".equalsIgnoreCase(action)) {
            return plugin.editAnnouncement(id, content) ? ok(map("edited", true)) : fail("公告不存在");
        }
        if ("delete".equalsIgnoreCase(action)) {
            return plugin.deleteAnnouncement(id) ? ok(map("deleted", true)) : fail("公告不存在");
        }
        return fail("不支持的公告操作: " + action);
    }

    /** 游戏内：存入个人物品仓库（主线程调用） */
    public Map<String, Object> depositItems(Player player, int quantity) {
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
        hand.setAmount(hand.getAmount() - quantity);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        plugin.getStorageManager().addToPlayerItemWarehouse(player.getUniqueId().toString(), base64, quantity);
        player.updateInventory();
        return ok(map("deposited", quantity));
    }

    /** 游戏内：存入货币仓库（主线程调用） */
    public Map<String, Object> depositMoney(Player player, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return fail("金额必须大于 0");
        }
        if (!EconomyUtil.hasBalance(player.getUniqueId(), amount)) {
            return fail("余额不足");
        }
        if (!EconomyUtil.withdraw(player.getUniqueId(), amount)) {
            return fail("扣款失败，请重试");
        }
        plugin.getStorageManager().addToMoneyWarehouse(player.getUniqueId().toString(), amount);
        return ok(map("deposited", amount));
    }
    // ===================== 内部实现 =====================

    private Map<String, Object> placeBuyInternal(String uuid, int itemId, BigDecimal price, int quantity) {
        if (quantity <= 0 || quantity > plugin.getMaxOrderQuantity()) {
            return fail("数量必须在 1 到 " + plugin.getMaxOrderQuantity() + " 之间");
        }
        if (!isValidPriceTick(price)) {
            return fail("价格必须是 " + plugin.getPriceTick() + " 的整数倍");
        }
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status != null && status.isSuspended()) {
            return fail("该品种已停牌");
        }
        if (plugin.isPriceLimitEnabled() && !priceWithinLimit(status, price)) {
            return fail("价格超出涨跌停范围");
        }
        BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
        if (plugin.getStorageManager().getMoneyWarehouseBalance(uuid).compareTo(total) < 0) {
            return fail("货币仓库余额不足（请先在游戏内 /se depositmoney 存入）");
        }
        if (!plugin.getStorageManager().takeFromMoneyWarehouse(uuid, total)) {
            return fail("仓库扣款失败，请重试");
        }
        Order order = newOrder(Order.OrderType.BUY, itemId, uuid, price, quantity);
        int orderId = plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            plugin.getStorageManager().addToMoneyWarehouse(uuid, total);
            return fail("创建订单失败");
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(uuid);
        escrow.setAssetType(EscrowEntry.AssetType.MONEY);
        escrow.setAmount(total);
        plugin.getStorageManager().insertEscrow(escrow);
        plugin.getLogger().info("[WebMarket] BUY_CREATE player=" + uuid + " order=" + orderId + " item=" + itemId + " price=" + price + " qty=" + quantity);
        matchWeb(order);
        refreshLowestSell(itemId);
        return ok(map("order_id", orderId, "message", "买单 #" + orderId + " 已创建"));
    }

    private Map<String, Object> placeSellInternal(String uuid, int itemId, BigDecimal price, int quantity) {
        if (quantity <= 0 || quantity > plugin.getMaxOrderQuantity()) {
            return fail("数量必须在 1 到 " + plugin.getMaxOrderQuantity() + " 之间");
        }
        if (!isValidPriceTick(price)) {
            return fail("价格必须是 " + plugin.getPriceTick() + " 的整数倍");
        }
        ExchangeItem item = plugin.getItemManager().getItem(itemId);
        if (item == null) {
            return fail("品种不存在");
        }
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status != null && status.isSuspended()) {
            return fail("该品种已停牌");
        }
        if (plugin.isPriceLimitEnabled() && !priceWithinLimit(status, price)) {
            return fail("价格超出涨跌停范围");
        }
        String base64 = item.getItemBase64();
        if (!plugin.getStorageManager().takeFromPlayerItemWarehouse(uuid, base64, quantity)) {
            return fail("个人物品仓库中该物品不足（请先在游戏内 /se deposit 存入）");
        }
        Order order = newOrder(Order.OrderType.SELL, itemId, uuid, price, quantity);
        int orderId = plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            plugin.getStorageManager().addToPlayerItemWarehouse(uuid, base64, quantity);
            return fail("创建订单失败");
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(uuid);
        escrow.setAssetType(EscrowEntry.AssetType.ITEM);
        escrow.setItemBase64(base64);
        escrow.setQuantity(quantity);
        plugin.getStorageManager().insertEscrow(escrow);
        EscrowEntry stored = plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.ITEM);
        if (stored == null || stored.getQuantity() != order.getRemainingQty()) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            plugin.getStorageManager().updateOrder(order);
            plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.ITEM);
            plugin.getStorageManager().addToPlayerItemWarehouse(uuid, base64, quantity);
            plugin.getLogger().severe("[WebMarket] SELL_CREATE_ABORT player=" + uuid + " order=" + orderId + " reason=escrow_verification_failed");
            return fail("托管写入校验失败，物品已退回仓库");
        }
        plugin.getLogger().info("[WebMarket] SELL_CREATE player=" + uuid + " order=" + orderId + " item=" + itemId + " price=" + price + " qty=" + quantity);
        matchWeb(order);
        refreshLowestSell(itemId);
        return ok(map("order_id", orderId, "message", "卖单 #" + orderId + " 已创建"));
    }

    private Map<String, Object> cancelInternal(String uuid, int orderId, boolean admin) {
        Order order = plugin.getStorageManager().getOrder(orderId);
        if (order == null) {
            return fail("订单不存在");
        }
        if (!admin && !order.getPlayerUuid().equalsIgnoreCase(uuid)) {
            return fail("这不是你的订单");
        }
        if (!order.isActive()) {
            return fail("订单已结束，无法取消");
        }
        String owner = order.getPlayerUuid();
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (escrow == null || escrow.getQuantity() < order.getRemainingQty()) {
                plugin.getLogger().severe("[WebMarket] SELL_CANCEL_BLOCKED player=" + uuid + " order=" + orderId
                        + " remaining=" + order.getRemainingQty()
                        + " escrow=" + (escrow == null ? "missing" : escrow.getQuantity()));
                return fail("该卖单托管数据异常，已阻止退款并记录日志");
            }
            order.setStatus(Order.OrderStatus.CANCELLED);
            plugin.getStorageManager().updateOrder(order);
            plugin.getStorageManager().addToPlayerItemWarehouse(owner, escrow.getItemBase64(), order.getRemainingQty());
            plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            plugin.getLogger().info("[WebMarket] SELL_CANCEL player=" + uuid + " order=" + orderId + " refund=" + order.getRemainingQty());
            refreshLowestSell(order.getItemId());
        } else {
            EscrowEntry escrow = plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            BigDecimal refund = escrow == null
                    ? order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQty()))
                    : escrow.getAmount();
            order.setStatus(Order.OrderStatus.CANCELLED);
            plugin.getStorageManager().updateOrder(order);
            plugin.getStorageManager().addToMoneyWarehouse(owner, refund);
            plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            plugin.getLogger().info("[WebMarket] BUY_CANCEL player=" + uuid + " order=" + orderId + " refund=" + refund);
        }
        return ok(map("cancelled", true, "order_id", orderId));
    }

    /** 撮合：与 OrderManager.matchOrder 相同规则（价格-时间优先） */
    private void matchWeb(Order newOrder) {
        if (newOrder.getOrderType() == Order.OrderType.BUY) {
            List<Order> sells = plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.SELL);
            for (Order sell : sells) {
                if (!newOrder.isActive() || sell.getPrice().compareTo(newOrder.getPrice()) > 0) {
                    break;
                }
                int qty = Math.min(newOrder.getRemainingQty(), sell.getRemainingQty());
                if (qty > 0) {
                    executeMatchWeb(newOrder, sell, qty);
                }
            }
        } else {
            List<Order> buys = plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.BUY);
            for (Order buy : buys) {
                if (!newOrder.isActive() || buy.getPrice().compareTo(newOrder.getPrice()) < 0) {
                    break;
                }
                int qty = Math.min(newOrder.getRemainingQty(), buy.getRemainingQty());
                if (qty > 0) {
                    executeMatchWeb(buy, newOrder, qty);
                }
            }
        }
    }
    /** 结算：与 OrderManager.executeMatch 一致，但买方向买方个人物品仓库交付 */
    private void executeMatchWeb(Order buyOrder, Order sellOrder, int quantity) {
        BigDecimal tradePrice = buyOrder.getCreatedAt().before(sellOrder.getCreatedAt())
                ? buyOrder.getPrice() : sellOrder.getPrice();
        BigDecimal totalAmount = tradePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal sellerFee = TaxCalculator.tax(totalAmount, plugin.getTaxRatePercent());
        BigDecimal sellerReceives = TaxCalculator.afterTax(totalAmount, plugin.getTaxRatePercent());
        String buyerUuid = buyOrder.getPlayerUuid();
        String sellerUuid = sellOrder.getPlayerUuid();

        EscrowEntry buyerEscrow = plugin.getStorageManager().getEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
        if (buyerEscrow != null) {
            BigDecimal matchedMoney = tradePrice.multiply(BigDecimal.valueOf(quantity));
            buyerEscrow.setAmount(buyerEscrow.getAmount().subtract(matchedMoney));
            if (buyerEscrow.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                plugin.getStorageManager().deleteEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            } else {
                plugin.getStorageManager().insertEscrow(buyerEscrow);
            }
        }
        EconomyUtil.deposit(UUID.fromString(sellerUuid), sellerReceives);

        EscrowEntry sellerEscrow = plugin.getStorageManager().getEscrow(sellOrder.getId(), EscrowEntry.AssetType.ITEM);
        if (sellerEscrow != null) {
            sellerEscrow.setQuantity(sellerEscrow.getQuantity() - quantity);
            if (sellerEscrow.getQuantity() <= 0) {
                plugin.getStorageManager().deleteEscrow(sellOrder.getId(), EscrowEntry.AssetType.ITEM);
            } else {
                plugin.getStorageManager().insertEscrow(sellerEscrow);
            }
        }
        ExchangeItem item = plugin.getItemManager().getItem(buyOrder.getItemId());
        if (item != null) {
            plugin.getStorageManager().addToPlayerItemWarehouse(buyerUuid, item.getItemBase64(), quantity);
        }

        buyOrder.setFilledQty(buyOrder.getFilledQty() + quantity);
        sellOrder.setFilledQty(sellOrder.getFilledQty() + quantity);
        if (buyOrder.getFilledQty() >= buyOrder.getQuantity()) {
            buyOrder.setStatus(Order.OrderStatus.CLOSED);
            EscrowEntry remaining = plugin.getStorageManager().getEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            if (remaining != null && remaining.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                plugin.getStorageManager().addToMoneyWarehouse(buyerUuid, remaining.getAmount());
                plugin.getStorageManager().deleteEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            }
        } else {
            buyOrder.setStatus(Order.OrderStatus.PARTIAL);
        }
        sellOrder.setStatus(sellOrder.getFilledQty() >= sellOrder.getQuantity()
                ? Order.OrderStatus.CLOSED : Order.OrderStatus.PARTIAL);
        plugin.getStorageManager().updateOrder(buyOrder);
        plugin.getStorageManager().updateOrder(sellOrder);

        Trade trade = new Trade();
        trade.setItemId(buyOrder.getItemId());
        trade.setBuyerUuid(buyerUuid);
        trade.setSellerUuid(sellerUuid);
        trade.setPrice(tradePrice);
        trade.setQuantity(quantity);
        trade.setTotalAmount(totalAmount);
        trade.setBuyerFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        trade.setSellerFee(sellerFee);
        trade.setBuyOrderId(buyOrder.getId());
        trade.setSellOrderId(sellOrder.getId());
        trade.setTradedAt(new Timestamp(System.currentTimeMillis()));
        plugin.getStorageManager().insertTrade(trade);
        plugin.collectTax(sellerFee);
        updateItemStatusAfterTrade(buyOrder.getItemId(), tradePrice, quantity);
        plugin.getLogger().info("[WebMarket] TRADE item=" + buyOrder.getItemId() + " qty=" + quantity
                + " price=" + tradePrice + " buyer=" + buyerUuid + " seller=" + sellerUuid);
    }

    private void updateItemStatusAfterTrade(int itemId, BigDecimal price, int quantity) {
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            status = new ItemStatus(itemId, false, price, price, price, price, quantity);
        } else {
            if (status.getLastClose() == null || status.getLastClose().compareTo(BigDecimal.ZERO) <= 0) {
                status.setLastOpen(price);
                status.setHighToday(price);
                status.setLowToday(price);
            } else {
                status.setHighToday(status.getHighToday() == null ? price : status.getHighToday().max(price));
                status.setLowToday(status.getLowToday() == null ? price : status.getLowToday().min(price));
            }
            status.setLastClose(price);
            status.setVolumeToday(status.getVolumeToday() + quantity);
        }
        plugin.getItemManager().updateItemStatus(status);
    }

    private void refreshLowestSell(int itemId) {
        ItemStatus status = plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            return;
        }
        BigDecimal lowest = lowestSellPrice(itemId);
        status.setLowestSellCurrent(lowest == null ? BigDecimal.ZERO : lowest);
        plugin.getItemManager().updateItemStatus(status);
    }

    private BigDecimal lowestSellPrice(int itemId) {
        List<Order> sells = plugin.getStorageManager().getActiveOrdersByItem(itemId, Order.OrderType.SELL);
        if (sells.isEmpty()) {
            return null;
        }
        return sells.get(0).getPrice();
    }

    private boolean isValidPriceTick(BigDecimal price) {
        if (price == null) {
            return false;
        }
        if (price.compareTo(BigDecimal.valueOf(plugin.getMinPrice())) < 0 || price.compareTo(BigDecimal.valueOf(plugin.getMaxPrice())) > 0) {
            return false;
        }
        BigDecimal tick = BigDecimal.valueOf(plugin.getPriceTick());
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).compareTo(price) == 0;
    }

    private boolean priceWithinLimit(ItemStatus status, BigDecimal price) {
        if (!plugin.isPriceLimitEnabled()) {
            return true;
        }
        BigDecimal reference = BigDecimal.ZERO;
        if (status != null) {
            if (status.getLastClose() != null && status.getLastClose().compareTo(BigDecimal.ZERO) > 0) {
                reference = status.getLastClose();
            } else if (status.getLowestSellCurrent() != null && status.getLowestSellCurrent().compareTo(BigDecimal.ZERO) > 0) {
                reference = status.getLowestSellCurrent();
            }
        }
        if (reference.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        BigDecimal up = reference.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(plugin.getLimitUpPercent() / 100.0)));
        BigDecimal down = reference.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(plugin.getLimitDownPercent() / 100.0)));
        return price.compareTo(down) >= 0 && price.compareTo(up) <= 0;
    }

    private Order newOrder(Order.OrderType type, int itemId, String uuid, BigDecimal price, int quantity) {
        Order order = new Order();
        order.setOrderType(type);
        order.setItemId(itemId);
        order.setPlayerUuid(uuid);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(0);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        return order;
    }
    // ===================== 视图转换与工具 =====================

    private Map<String, Object> itemView(ExchangeItem item) {
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
        }
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
        view.put("price", order.getPrice());
        view.put("quantity", order.getQuantity());
        view.put("filled_qty", order.getFilledQty());
        view.put("remaining_qty", order.getRemainingQty());
        view.put("status", order.getStatus().name());
        view.put("created_at", order.getCreatedAt());
        ExchangeItem item = plugin.getItemManager().getItem(order.getItemId());
        view.put("item_name", item == null ? null : item.getDisplayName());
        return view;
    }

    private Map<String, Object> tradeView(Trade trade) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", trade.getId());
        view.put("item_id", trade.getItemId());
        view.put("buyer_uuid", trade.getBuyerUuid());
        view.put("seller_uuid", trade.getSellerUuid());
        view.put("price", trade.getPrice());
        view.put("quantity", trade.getQuantity());
        view.put("total_amount", trade.getTotalAmount());
        view.put("seller_fee", trade.getSellerFee());
        view.put("traded_at", trade.getTradedAt());
        ExchangeItem item = plugin.getItemManager().getItem(trade.getItemId());
        view.put("item_name", item == null ? null : item.getDisplayName());
        return view;
    }

    /** 聚合盘口：按价格分组汇总剩余量，最多 5 档 */
    private List<Map<String, Object>> aggregateLevels(List<Order> orders, boolean ascending) {
        Map<String, BigDecimal> levelQty = new LinkedHashMap<String, BigDecimal>();
        for (Order order : orders) {
            BigDecimal existing = levelQty.get(order.getPrice().toPlainString());
            levelQty.put(order.getPrice().toPlainString(), existing == null
                    ? BigDecimal.valueOf(order.getRemainingQty())
                    : existing.add(BigDecimal.valueOf(order.getRemainingQty())));
        }
        List<Map<String, Object>> levels = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, BigDecimal> entry : levelQty.entrySet()) {
            Map<String, Object> level = new LinkedHashMap<String, Object>();
            level.put("price", new BigDecimal(entry.getKey()));
            level.put("quantity", entry.getValue());
            levels.add(level);
        }
        java.util.Collections.sort(levels, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                BigDecimal pa = (BigDecimal) a.get("price");
                BigDecimal pb = (BigDecimal) b.get("price");
                return ascending ? pa.compareTo(pb) : pb.compareTo(pa);
            }
        });
        return levels.size() > 5 ? levels.subList(0, 5) : levels;
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
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("[WebMarket] 主线程执行失败: " + e.getMessage());
            return fail("服务器繁忙，请稍后再试");
        }
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

    // ===================== 历史行情统计（管理员市场数据） =====================

    /**
     * 历史价格统计与销量排行：从成交记录计算每个品种最近 days 天的
     * 每日 OHLC（开/高/低/收）+ 成交量/额，并按成交量降序给出销量排行。
     */
    public Map<String, Object> getMarketStats(int days) {
        if (days < 1) {
            days = 30;
        }
        if (days > 365) {
            days = 365;
        }
        long cutoff = System.currentTimeMillis() - (long) days * 86400000L;
        List<Trade> trades = plugin.getStorageManager().getAllTrades(100000);
        trades.sort((a, b) -> Long.compare(a.getTradedAt().getTime(), b.getTradedAt().getTime()));

        Map<Integer, Map<String, DayStat>> byItem = new LinkedHashMap<Integer, Map<String, DayStat>>();
        Map<Integer, ItemStat> itemTotals = new LinkedHashMap<Integer, ItemStat>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd");

        for (Trade trade : trades) {
            if (trade.getTradedAt() == null || trade.getTradedAt().getTime() < cutoff) {
                continue;
            }
            String date = fmt.format(trade.getTradedAt());
            Map<String, DayStat> dayMap = byItem.get(trade.getItemId());
            if (dayMap == null) {
                dayMap = new LinkedHashMap<String, DayStat>();
                byItem.put(trade.getItemId(), dayMap);
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

            ItemStat stat = itemTotals.get(trade.getItemId());
            if (stat == null) {
                stat = new ItemStat();
                itemTotals.put(trade.getItemId(), stat);
            }
            stat.volume += trade.getQuantity();
            stat.amount = stat.amount.add(trade.getTotalAmount());
            stat.lastPrice = p;
        }

        Map<String, Object> items = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> ranking = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Integer, ItemStat> entry : itemTotals.entrySet()) {
            int itemId = entry.getKey();
            ItemStat stat = entry.getValue();
            ExchangeItem item = plugin.getItemManager().getItem(itemId);
            String name = item == null ? ("#" + itemId)
                    : (item.getDisplayName() != null ? item.getDisplayName() : item.getItemName());
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
                    Map<String, Object> h = new LinkedHashMap<String, Object>();
                    h.put("date", dayEntry.getKey());
                    h.put("open", d.open);
                    h.put("high", d.high);
                    h.put("low", d.low);
                    h.put("close", d.close);
                    h.put("volume", d.volume);
                    history.add(h);
                }
            }
            view.put("history", history);
            items.put(String.valueOf(itemId), view);

            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("item_id", itemId);
            r.put("item_name", name);
            r.put("volume", stat.volume);
            r.put("amount", stat.amount);
            ranking.add(r);
        }
        ranking.sort((a, b) -> Integer.compare(((Number) b.get("volume")).intValue(), ((Number) a.get("volume")).intValue()));

        int totalVolume = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ItemStat stat : itemTotals.values()) {
            totalVolume += stat.volume;
            totalAmount = totalAmount.add(stat.amount);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("days", days);
        data.put("items", items);
        data.put("ranking", ranking);
        data.put("total_volume", totalVolume);
        data.put("total_amount", totalAmount);
        data.put("total_items", itemTotals.size());
        return ok(data);
    }

    /** 单个品种单日统计 */
    private static final class DayStat {
        int volume;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
    }

    /** 单个品种统计窗口合计 */
    private static final class ItemStat {
        int volume;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal lastPrice;
    }
}