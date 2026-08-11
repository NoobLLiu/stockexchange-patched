/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 */
package com.github.exchange.manager;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.ItemStatus;
import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import com.github.exchange.util.EconomyUtil;
import com.github.exchange.util.InventoryDelivery;
import com.github.exchange.util.ItemDisplayNames;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.MarketGuiItem;
import com.github.exchange.util.SpecialCategory;
import com.github.exchange.util.TaxCalculator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class OrderManager {
    private final StockExchangePlugin plugin;

    public OrderManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized String placeSellOrder(Player player, ExchangeItem exchangeItem, BigDecimal price, int quantity) {
        if (player == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        ItemStack actualItem;
        if (category != null) {
            actualItem = this.findSingleCategoryItem(player, category);
            if (actualItem == null) {
                return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u53ef\u4e0a\u67b6\u7684\u300c" + category.displayName()
                    + "\u300d\u7269\u54c1\uff0c\u6216\u5305\u542b\u591a\u79cd\u540c\u7c7b\u7269\u54c1\uff0c\u8bf7\u4f7f\u7528\u4e0a\u67b6\u83dc\u5355\u9009\u62e9\u5177\u4f53\u7269\u54c1\u3002";
            }
        } else {
            actualItem = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        }
        return this.placeSellOrderInternal(player, exchangeItem, actualItem, null, price, quantity, false);
    }

    public synchronized String placeSellOrder(Player player, ExchangeItem exchangeItem, ItemStack actualItem, BigDecimal price, int quantity) {
        return this.placeSellOrderInternal(player, exchangeItem, actualItem, null, price, quantity, false);
    }

    public synchronized String placeSellOrderFromReserved(Player player, ExchangeItem exchangeItem, String itemBase64, BigDecimal price, int quantity) {
        if (itemBase64 == null || itemBase64.isBlank()) {
            return "\u00a7c\u7269\u54c1\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        ItemStack actualItem = ItemSerializer.itemFromBase64(itemBase64);
        if (actualItem == null) {
            return "\u00a7c\u7269\u54c1\u6570\u636e\u635f\u574f\uff0c\u65e0\u6cd5\u4e0a\u67b6\u3002";
        }
        return this.placeSellOrderInternal(player, exchangeItem, actualItem, itemBase64, price, quantity, true);
    }

    private synchronized String placeSellOrderInternal(Player player, ExchangeItem exchangeItem, ItemStack actualItem, String reservedBase64, BigDecimal price, int quantity, boolean fromReserved) {
        SellOrderCreation creation = this.createSellOrderAndEscrow(player, exchangeItem, actualItem, reservedBase64, price, quantity, fromReserved);
        if (creation.order == null) {
            return creation.error;
        }
        Order order = creation.order;
        exchangeItem.setLastStockedAt(new Timestamp(System.currentTimeMillis()));
        exchangeItem.setLastEmptyAt(null);
        this.plugin.getStorageManager().updateExchangeItem(exchangeItem);
        this.plugin.getLogger().info("[AssetAudit] SELL_CREATE player=" + player.getUniqueId()
            + " order=" + order.getId() + " item=" + exchangeItem.getId() + " removed=" + quantity
            + " escrow=" + order.getRemainingQty());
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        this.broadcastNewListing(exchangeItem);
        return "\u00a7a\u5356\u5355 #" + order.getId() + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: " + price + ", \u6570\u91cf: " + quantity;
    }

    private SellOrderCreation createSellOrderAndEscrow(Player player, ExchangeItem exchangeItem, ItemStack actualItem, BigDecimal price, int quantity, boolean fromReserved) {
        return this.createSellOrderAndEscrow(player, exchangeItem, actualItem, null, price, quantity, fromReserved);
    }

    private SellOrderCreation createSellOrderAndEscrow(Player player, ExchangeItem exchangeItem, ItemStack actualItem, String reservedBase64, BigDecimal price, int quantity, boolean fromReserved) {
        if (player == null || exchangeItem == null) {
            return new SellOrderCreation(null, "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002", null);
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return new SellOrderCreation(null, this.plugin.growthAccessMessage(player), null);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return new SellOrderCreation(null, "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002", null);
        }
        if (!this.isPriceInConfiguredRange(price)) {
            return new SellOrderCreation(null, "\u00a7c\u4ef7\u683c\u5fc5\u987b\u5728 " + this.plugin.getMinPrice()
                + " \u5230 " + this.plugin.getMaxPrice() + " \u4e4b\u95f4\u3002", null);
        }
        if (!this.isValidPriceTick(price)) {
            return new SellOrderCreation(null, "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002", null);
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return new SellOrderCreation(null, "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002", null);
        }
        ItemStack itemStack = actualItem != null ? actualItem : ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return new SellOrderCreation(null, "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002", null);
        }
        String itemBase64 = reservedBase64 != null && !reservedBase64.isBlank()
            ? reservedBase64
            : ItemSerializer.itemToBase64(itemStack);
        if (itemBase64 == null) {
            return new SellOrderCreation(null, "\u00a7c\u7269\u54c1\u5e8f\u5217\u5316\u5931\u8d25\u3002", null);
        }
        InventoryRemoval removal = null;
        if (fromReserved) {
            if (!this.plugin.getStorageManager().takeFromPlayerItemWarehouse(
                player.getUniqueId().toString(), itemBase64, quantity)) {
                return new SellOrderCreation(null, "\u00a7c\u4f60\u7684\u4ea4\u6613\u4ed3\u5e93\u4e2d\u6ca1\u6709\u8db3\u591f\u7684\u7269\u54c1\u3002", null);
            }
        } else {
            int beforeCount = this.countSimilarItems(player, itemStack);
            if (beforeCount < quantity) {
                return new SellOrderCreation(null, "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u8db3\u591f\u7684\u7269\u54c1\u3002", null);
            }
            removal = this.removeSimilarItems(player, itemStack, quantity);
            if (removal == null) {
                return new SellOrderCreation(null, "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002", null);
            }
            int afterCount = this.countSimilarItems(player, itemStack);
            if (afterCount != beforeCount - quantity) {
                removal.rollback();
                return new SellOrderCreation(null, "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002", null);
            }
        }
        Order order = new Order();
        order.setOrderType(Order.OrderType.SELL);
        order.setItemId(exchangeItem.getId());
        order.setPlayerUuid(player.getUniqueId().toString());
        order.setPlayerName(player.getName());
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(0);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            this.rollbackSellRemoval(player, itemBase64, quantity, removal);
            return new SellOrderCreation(null, "\u00a7c\u521b\u5efa\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002", null);
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(player.getUniqueId().toString());
        escrow.setAssetType(EscrowEntry.AssetType.ITEM);
        escrow.setAmount(BigDecimal.ZERO);
        escrow.setItemBase64(itemBase64);
        escrow.setQuantity(quantity);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(order);
            this.rollbackSellRemoval(player, itemBase64, quantity, removal);
            return new SellOrderCreation(null, "\u00a7c\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u7269\u54c1\u5df2\u539f\u69fd\u4f4d\u6062\u590d\u3002", null);
        }
        EscrowEntry storedEscrow = this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.ITEM);
        if (!this.isValidSellEscrow(order, storedEscrow)) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(order);
            this.plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.ITEM);
            this.rollbackSellRemoval(player, itemBase64, quantity, removal);
            this.plugin.getLogger().severe("[AssetAudit] SELL_CREATE_ABORT player=" + player.getUniqueId()
                + " order=" + orderId + " item=" + exchangeItem.getId() + " quantity=" + quantity
                + " reason=escrow_verification_failed");
            return new SellOrderCreation(null, "\u00a7c\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u7269\u54c1\u5df2\u539f\u69fd\u4f4d\u6062\u590d\u3002", null);
        }
        return new SellOrderCreation(order, null, removal);
    }

    private void rollbackSellRemoval(Player player, String itemBase64, int quantity, InventoryRemoval removal) {
        if (removal != null) {
            removal.rollback();
        } else if (itemBase64 != null && quantity > 0) {
            this.plugin.getStorageManager().addToPlayerItemWarehouse(
                player.getUniqueId().toString(), itemBase64, quantity);
        }
    }

    public synchronized String placeBuyOrder(Player player, ExchangeItem exchangeItem, BigDecimal price, int quantity) {
        if (player == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isPriceInConfiguredRange(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u5728 " + this.plugin.getMinPrice()
                + " \u5230 " + this.plugin.getMaxPrice() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isValidPriceTick(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002";
        }
        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal tax = TaxCalculator.tax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal chargedTotal = TaxCalculator.withTax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal escrowTotal = totalCost;
        UUID playerUuid = player.getUniqueId();
        if (!EconomyUtil.hasBalance(playerUuid, chargedTotal)) {
            return "\u00a7c\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + chargedTotal
                + " \uff08\u542b\u4ea4\u6613\u7a0e " + tax + "\uff09\uff0c\u5f53\u524d: " + EconomyUtil.getBalance(playerUuid);
        }
        if (!EconomyUtil.withdraw(playerUuid, chargedTotal)) {
            return "\u00a7c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        Order order = new Order();
        order.setOrderType(Order.OrderType.BUY);
        order.setItemId(exchangeItem.getId());
        order.setPlayerUuid(player.getUniqueId().toString());
        order.setPlayerName(player.getName());
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(0);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            this.deliverRefundedMoney(playerUuid, chargedTotal);
            return "\u00a7c\u521b\u5efa\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(player.getUniqueId().toString());
        escrow.setAssetType(EscrowEntry.AssetType.MONEY);
        escrow.setAmount(escrowTotal);
        escrow.setItemBase64(null);
        escrow.setQuantity(0);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)
            || !this.isValidMoneyEscrow(order, this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.MONEY), escrowTotal)) {
            this.abortBuyOrderCreation(order, chargedTotal);
            return "\u00a7c\u8ba2\u5355\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u5df2\u9000\u56de\u6263\u9664\u7684\u661f\u5149\u70b9\u3002";
        }
        this.plugin.collectTax(tax);
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        this.broadcastNewBuyRequest(exchangeItem, price);
        return "\u00a7a\u4e70\u5355 #" + orderId + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: " + price
            + ", \u6570\u91cf: " + quantity + "\uff0c\u5546\u54c1\u603b\u4ef7: " + totalCost
            + "\uff0c\u4ea4\u6613\u7a0e: " + tax + "\uff0c\u5b9e\u9645\u6263\u6b3e: " + chargedTotal;
    }

    public synchronized String marketBuy(Player player, ExchangeItem exchangeItem, int quantity) {
        if (player == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u5e02\u4ef7\u4ea4\u6613\u3002";
        }
        List<Order> sellOrders = this.getActiveOrders(exchangeItem.getId(), Order.OrderType.SELL);
        if (sellOrders.isEmpty()) {
            return "\u00a7c\u5f53\u524d\u6ca1\u6709\u53ef\u7528\u5356\u5355\uff0c\u65e0\u6cd5\u4e70\u5165\u3002";
        }
        long available = 0L;
        BigDecimal topPrice = BigDecimal.ZERO;
        for (Order sellOrder : sellOrders) {
            available += sellOrder.getRemainingQty();
            topPrice = sellOrder.getPrice();
            if (available >= quantity) {
                break;
            }
        }
        if (available < quantity) {
            return "\u00a7c\u5f53\u524d\u5e02\u573a\u5b58\u8d27\u4e0d\u8db3\uff0c\u53ef\u7528: " + available + " \u4e2a\u3002";
        }
        String result = this.placeBuyOrder(player, exchangeItem, topPrice, quantity);
        return result.startsWith("\u00a7c")
            ? result
            : "\u00a7a\u5df2\u6309\u5f53\u524d\u5e02\u573a\u4ef7\u683c\u63d0\u4ea4\u4e70\u5165\u3002 " + result;
    }

    public synchronized String directBuyFromSellOrder(Player player, int sellOrderId, int quantity) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u8d2d\u4e70\u7269\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        Order sellOrder = this.plugin.getStorageManager().getOrder(sellOrderId);
        if (sellOrder == null || sellOrder.getOrderType() != Order.OrderType.SELL || !sellOrder.isActive()) {
            return "\u00a7c\u8be5\u5356\u5355\u5df2\u4e0d\u53ef\u7528\u3002";
        }
        if (sellOrder.getPlayerUuid().equals(player.getUniqueId().toString())) {
            return "\u00a7c\u4e0d\u80fd\u8d2d\u4e70\u81ea\u5df1\u4e0a\u67b6\u7684\u7269\u54c1\u3002";
        }
        if (sellOrder.getRemainingQty() < quantity) {
            return "\u00a7c\u8be5\u5356\u5355\u5269\u4f59\u6570\u91cf\u4e0d\u8db3\u3002";
        }
        ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(sellOrder.getItemId());
        if (exchangeItem == null) {
            return "\u00a7c\u8be5\u5546\u54c1\u54c1\u79cd\u4e0d\u5b58\u5728\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u8d2d\u4e70\u3002";
        }
        BigDecimal totalCost = sellOrder.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal tax = TaxCalculator.tax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal chargedTotal = TaxCalculator.withTax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal escrowTotal = totalCost;
        UUID playerUuid = player.getUniqueId();
        if (!EconomyUtil.hasBalance(playerUuid, chargedTotal)) {
            return "\u00a7c\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + chargedTotal
                + " \uff08\u542b\u4ea4\u6613\u7a0e " + tax + "\uff09\uff0c\u5f53\u524d: " + EconomyUtil.getBalance(playerUuid);
        }
        if (!EconomyUtil.withdraw(playerUuid, chargedTotal)) {
            return "\u00a7c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        Order buyOrder = new Order();
        buyOrder.setOrderType(Order.OrderType.BUY);
        buyOrder.setItemId(exchangeItem.getId());
        buyOrder.setPlayerUuid(playerUuid.toString());
        buyOrder.setPlayerName(player.getName());
        buyOrder.setPrice(sellOrder.getPrice());
        buyOrder.setQuantity(quantity);
        buyOrder.setFilledQty(0);
        buyOrder.setStatus(Order.OrderStatus.OPEN);
        buyOrder.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        buyOrder.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(buyOrder);
        if (orderId <= 0) {
            this.deliverRefundedMoney(playerUuid, chargedTotal);
            return "\u00a7c\u521b\u5efa\u8d2d\u4e70\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        buyOrder.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(playerUuid.toString());
        escrow.setAssetType(EscrowEntry.AssetType.MONEY);
        escrow.setAmount(escrowTotal);
        escrow.setItemBase64(null);
        escrow.setQuantity(0);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)
            || !this.isValidMoneyEscrow(buyOrder, this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.MONEY), escrowTotal)) {
            this.abortBuyOrderCreation(buyOrder, chargedTotal);
            return "\u00a7c\u8d2d\u4e70\u8ba2\u5355\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u5df2\u9000\u56de\u6263\u9664\u7684\u661f\u5149\u70b9\u3002";
        }
        if (!this.executeMatch(buyOrder, sellOrder, quantity)) {
            String cancelResult = this.cancelOrder(player, orderId);
            return "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002 " + cancelResult;
        }
        this.plugin.collectTax(tax);
        return "\u00a7a\u5df2\u8d2d\u4e70 " + exchangeItem.getDisplayName() + " x" + quantity
            + "\uff0c\u6210\u4ea4\u4ef7: " + sellOrder.getPrice()
            + "\uff0c\u4ea4\u6613\u7a0e: " + tax + "\uff0c\u5b9e\u9645\u6263\u6b3e: " + chargedTotal;
    }

    public synchronized String directSellToBuyOrder(Player player, int buyOrderId, int quantity) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u51fa\u552e\u7269\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        Order buyOrder = this.plugin.getStorageManager().getOrder(buyOrderId);
        if (buyOrder == null || buyOrder.getOrderType() != Order.OrderType.BUY || !buyOrder.isActive()) {
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u5df2\u4e0d\u53ef\u7528\u3002";
        }
        if (buyOrder.getPlayerUuid().equals(player.getUniqueId().toString())) {
            return "\u00a7c\u4e0d\u80fd\u51fa\u552e\u7ed9\u81ea\u5df1\u7684\u6c42\u8d2d\u5355\u3002";
        }
        if (buyOrder.getRemainingQty() < quantity) {
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u5269\u4f59\u6570\u91cf\u4e0d\u8db3\u3002";
        }
        ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(buyOrder.getItemId());
        if (exchangeItem == null) {
            return "\u00a7c\u8be5\u5546\u54c1\u54c1\u79cd\u4e0d\u5b58\u5728\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u4f9b\u8d27\u3002";
        }
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (category != null) {
            return this.supplySpecialCategoryToBuyOrder(player, buyOrder, exchangeItem, quantity);
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        SellOrderCreation creation = this.createSellOrderAndEscrow(
            player, exchangeItem, itemStack, buyOrder.getPrice(), quantity, false);
        if (creation.order == null) {
            return creation.error;
        }
        Order sellOrder = creation.order;
        this.plugin.getLogger().info("[AssetAudit] SELL_TO_BUY player=" + player.getUniqueId()
            + " sellOrder=" + sellOrder.getId() + " buyOrder=" + buyOrderId + " item=" + exchangeItem.getId()
            + " removed=" + (creation.removal == null ? quantity : creation.removal.removedQuantity())
            + " escrow=" + sellOrder.getRemainingQty());
        if (!this.executeMatch(buyOrder, sellOrder, quantity)) {
            String cancelResult = this.cancelOrder(player, sellOrder.getId());
            return "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u7269\u54c1\u672a\u6210\u4ea4\u3002 " + cancelResult;
        }
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5df2\u51fa\u552e " + exchangeItem.getDisplayName() + " x" + quantity + "\uff0c\u6210\u4ea4\u4ef7: " + buyOrder.getPrice();
    }

    private String supplySpecialCategoryToBuyOrder(Player player, Order buyOrder, ExchangeItem exchangeItem, int quantity) {
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (player == null || buyOrder == null || exchangeItem == null || category == null || quantity <= 0) {
            return "\u00a7c\u65e0\u6548\u7684\u8ba2\u5355\u6216\u6570\u91cf\u3002";
        }
        Map<String, CategoryItem> distinct = new LinkedHashMap<String, CategoryItem>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SlotRemovalPlan.STORAGE_SLOT_COUNT; ++slot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR || MarketGuiItem.isMarked(stack)) {
                continue;
            }
            if (SpecialCategory.of(stack) != category) {
                continue;
            }
            ItemStack single = stack.clone();
            single.setAmount(1);
            String base64 = ItemSerializer.itemToBase64(single);
            if (base64 == null) {
                continue;
            }
            CategoryItem entry = distinct.get(base64);
            if (entry == null) {
                distinct.put(base64, new CategoryItem(single, stack.getAmount()));
            } else {
                entry.count += stack.getAmount();
            }
        }
        int total = 0;
        for (CategoryItem entry : distinct.values()) {
            total += entry.count;
        }
        if (total < quantity) {
            return "\u00a7c\u80cc\u5305\u4e2d\u7684\u300c" + category.displayName() + "\u300d\u7269\u54c1\u4e0d\u8db3 "
                + quantity + " \u4e2a\u3002";
        }
        int supplied = 0;
        int remaining = quantity;
        for (CategoryItem entry : distinct.values()) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(entry.count, remaining);
            int orderRemaining = take;
            while (orderRemaining > 0) {
                int chunk = Math.min(orderRemaining, this.plugin.getMaxOrderQuantity());
                SellOrderCreation creation = this.createSellOrderAndEscrow(
                    player, exchangeItem, entry.item, buyOrder.getPrice(), chunk, false);
                if (creation.order == null) {
                    return supplied > 0
                        ? "\u00a7a\u5df2\u4f9b\u8d27 " + supplied + " \u4e2a\uff0c\u540e\u7eed\u4ea4\u6613\u5931\u8d25\uff1a" + creation.error
                        : creation.error;
                }
                this.plugin.getLogger().info("[AssetAudit] SELL_TO_BUY player=" + player.getUniqueId()
                    + " sellOrder=" + creation.order.getId() + " buyOrder=" + buyOrder.getId()
                    + " item=" + exchangeItem.getId()
                    + " removed=" + (creation.removal == null ? chunk : creation.removal.removedQuantity())
                    + " escrow=" + creation.order.getRemainingQty());
                if (!this.executeMatch(buyOrder, creation.order, chunk)) {
                    String cancelResult = this.cancelOrder(player, creation.order.getId());
                    return supplied > 0
                        ? "\u00a7a\u5df2\u4f9b\u8d27 " + supplied + " \u4e2a\uff0c\u540e\u7eed\u4ea4\u6613\u672a\u5b8c\u6210\u3002 " + cancelResult
                        : "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u7269\u54c1\u672a\u6210\u4ea4\u3002 " + cancelResult;
                }
                supplied += chunk;
                orderRemaining -= chunk;
                remaining -= chunk;
            }
        }
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5df2\u51fa\u552e " + exchangeItem.getDisplayName() + " x" + supplied
            + "\uff0c\u6210\u4ea4\u4ef7: " + buyOrder.getPrice();
    }

    public synchronized String marketSell(Player player, ExchangeItem exchangeItem, int quantity) {
        if (player == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        BigDecimal marketPrice;
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u5e02\u4ef7\u4ea4\u6613\u3002";
        }
        BigDecimal bigDecimal = marketPrice = status != null ? status.getLastClose() : BigDecimal.ZERO;
        if (marketPrice == null || marketPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "\u00a7c\u5f53\u524d\u65e0\u6700\u65b0\u6210\u4ea4\u4ef7\uff0c\u65e0\u6cd5\u6309\u5e02\u4ef7\u4e0b\u5355\u3002";
        }
        String result = this.placeSellOrder(player, exchangeItem, marketPrice, quantity);
        return result.startsWith("\u00a7c")
            ? result
            : "\u00a7a\u5df2\u6309\u5e02\u4ef7(" + marketPrice + ")\u63d0\u4ea4\u5356\u5355\u3002" + result;
    }

    public synchronized String quickSellAll(Player player, ExchangeItem exchangeItem) {
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        BigDecimal lowestPrice = this.getLowestSellPrice(exchangeItem.getId());
        if (lowestPrice == null || lowestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
            if (status != null && status.getLastClose() != null && status.getLastClose().compareTo(BigDecimal.ZERO) > 0) {
                lowestPrice = status.getLastClose();
            }
        }
        if (lowestPrice == null || lowestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "\u00a7c\u5f53\u524d\u65e0\u53c2\u8003\u4ef7\u683c\uff0c\u8bf7\u4f7f\u7528\u666e\u901a\u4e0a\u67b6\u624b\u52a8\u8f93\u5165\u4ef7\u683c\u3002";
        }
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (category != null) {
            ItemStack actualItem = this.findSingleCategoryItem(player, category);
            if (actualItem == null) {
                return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u53ef\u4e0a\u67b6\u7684\u300c" + category.displayName()
                    + "\u300d\u7269\u54c1\uff0c\u6216\u5305\u542b\u591a\u79cd\u540c\u7c7b\u7269\u54c1\uff0c\u8bf7\u4f7f\u7528\u4e0a\u67b6\u83dc\u5355\u3002";
            }
            int totalCount = this.countSimilarItems(player, actualItem);
            if (totalCount <= 0) {
                return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u8be5\u7c7b\u578b\u7269\u54c1\u3002";
            }
            int quantity = Math.min(totalCount, this.plugin.getMaxOrderQuantity());
            return this.placeSellOrder(player, exchangeItem, actualItem, lowestPrice, quantity);
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        int totalCount = this.countSimilarItems(player, itemStack);
        if (totalCount <= 0) {
            return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u8be5\u7c7b\u578b\u7269\u54c1\u3002";
        }
        int quantity = Math.min(totalCount, this.plugin.getMaxOrderQuantity());
        return this.placeSellOrder(player, exchangeItem, lowestPrice, quantity);
    }

    public synchronized SupplyPlanner.Plan getSupplyPlan(Player player, ExchangeItem exchangeItem) {
        if (player == null || exchangeItem == null) {
            return SupplyPlanner.plan(0, null);
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return SupplyPlanner.plan(0, null);
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return SupplyPlanner.plan(0, null);
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return SupplyPlanner.plan(0, null);
        }
        String playerUuid = player.getUniqueId().toString();
        List<Order> buyOrders = new ArrayList<Order>(
            this.getActiveOrders(exchangeItem.getId(), Order.OrderType.BUY)
        );
        buyOrders.removeIf(order -> playerUuid.equals(order.getPlayerUuid()));
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        int available = category != null
            ? this.countCategoryItems(player, category)
            : this.countSimilarItems(player, itemStack);
        return SupplyPlanner.plan(available, buyOrders);
    }

    public synchronized String supplyAllToBuyOrders(Player player, ExchangeItem exchangeItem) {
        if (player == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        SupplyPlanner.Plan plan = this.getSupplyPlan(player, exchangeItem);
        if (plan.matchedQuantity() <= 0) {
            if (plan.availableQuantity() > 0) {
                return "\u00a7c\u5f53\u524d\u6ca1\u6709\u5176\u4ed6\u73a9\u5bb6\u7684\u53ef\u4f9b\u8d27\u6c42\u8d2d\u5355\uff08\u4e0d\u80fd\u4f9b\u8d27\u7ed9\u81ea\u5df1\u7684\u6c42\u8d2d\u5355\uff09\u3002";
            }
            return "\u00a7c\u5f53\u524d\u6ca1\u6709\u53ef\u4f9b\u8d27\u7684\u6c42\u8d2d\u5355\uff0c\u6216\u80cc\u5305\u4e2d\u6ca1\u6709\u53ef\u7528\u7269\u54c1\u3002";
        }
        int supplied = 0;
        BigDecimal grossAmount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SupplyPlanner.Allocation allocation : plan.allocations()) {
            int remaining = allocation.quantity();
            boolean allocationFailed = false;
            while (remaining > 0) {
                int chunk = Math.min(remaining, this.plugin.getMaxOrderQuantity());
                String result = this.directSellToBuyOrder(
                    player,
                    allocation.order().getId(),
                    chunk
                );
                if (result == null || result.startsWith("\u00a7c")) {
                    allocationFailed = true;
                    break;
                }
                supplied += chunk;
                BigDecimal amount = allocation.order().getPrice()
                    .multiply(BigDecimal.valueOf(chunk));
                grossAmount = grossAmount.add(amount);
                tax = tax.add(TaxCalculator.tax(amount, this.plugin.getTaxRatePercent()));
                remaining -= chunk;
            }
            if (allocationFailed) {
                break;
            }
        }
        if (supplied <= 0) {
            return "\u00a7c\u4e00\u952e\u4f9b\u8d27\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u80cc\u5305\u548c\u6c42\u8d2d\u5355\u3002";
        }
        BigDecimal received = grossAmount.subtract(tax);
        String completion = supplied == plan.matchedQuantity() ? "\u5b8c\u6210" : "\u90e8\u5206\u5b8c\u6210";
        return "\u00a7a\u4e00\u952e\u4f9b\u8d27" + completion + "\uff1a" + supplied + " \u4e2a"
            + "\uff0c\u9884\u8ba1\u6210\u4ea4\u989d: " + grossAmount.toPlainString()
            + "\uff0c\u9884\u8ba1\u5230\u8d26: " + received.toPlainString()
            + " \uff08\u4ea4\u6613\u7a0e " + tax.toPlainString() + "\uff09";
    }

    private int countSimilarItems(Player player, ItemStack target) {
        String targetHash = ItemSerializer.calculateNbtHash(target);
        boolean strictHash = targetHash != null && !targetHash.isEmpty();
        int total = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SlotRemovalPlan.STORAGE_SLOT_COUNT; ++slot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !this.isSameTradableItem(stack, target, targetHash, strictHash)) continue;
            total += stack.getAmount();
        }
        return total;
    }

    private InventoryRemoval removeSimilarItems(Player player, ItemStack target, int quantity) {
        PlayerInventory inventory = player.getInventory();
        String targetHash = ItemSerializer.calculateNbtHash(target);
        boolean strictHash = targetHash != null && !targetHash.isEmpty();
        int[] available = new int[SlotRemovalPlan.STORAGE_SLOT_COUNT];
        ItemStack[] originals = new ItemStack[SlotRemovalPlan.STORAGE_SLOT_COUNT];

        for (int slot = 0; slot < SlotRemovalPlan.STORAGE_SLOT_COUNT; ++slot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !this.isSameTradableItem(stack, target, targetHash, strictHash)) {
                continue;
            }
            available[slot] = stack.getAmount();
            originals[slot] = stack.clone();
        }

        int[] removals = SlotRemovalPlan.create(available, quantity);
        if (removals == null) {
            return null;
        }

        for (int slot = 0; slot < removals.length; ++slot) {
            int removeAmount = removals[slot];
            if (removeAmount <= 0) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (current == null
                || !this.isSameTradableItem(current, target, targetHash, strictHash)
                || current.getAmount() != available[slot]) {
                return null;
            }
        }

        for (int slot = 0; slot < removals.length; ++slot) {
            int removeAmount = removals[slot];
            if (removeAmount <= 0) {
                continue;
            }
            int remaining = available[slot] - removeAmount;
            if (remaining <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack updated = originals[slot].clone();
                updated.setAmount(remaining);
                inventory.setItem(slot, updated);
            }
        }

        InventoryRemoval receipt = new InventoryRemoval(player, originals, removals, quantity);
        if (!receipt.matchesAppliedState()) {
            receipt.rollback();
            return null;
        }
        player.updateInventory();
        return receipt;
    }

    private boolean isSameTradableItem(ItemStack stack, ItemStack target, String targetHash, boolean strictHash) {
        if (stack == null || target == null) {
            return false;
        }
        if (MarketGuiItem.isMarked(stack)) {
            return false;
        }
        if (stack.getType() != target.getType()) {
            return false;
        }
        String stackHash = ItemSerializer.calculateNbtHash(stack);
        if (strictHash && targetHash.equals(stackHash)) {
            return true;
        }
        ItemStack normalizedTarget = target.clone();
        ItemStack normalizedStack = stack.clone();
        normalizedTarget.setAmount(1);
        normalizedStack.setAmount(1);
        String targetBase64 = ItemSerializer.itemToBase64(normalizedTarget);
        String stackBase64 = ItemSerializer.itemToBase64(normalizedStack);
        if (targetBase64 != null && stackBase64 != null) {
            return targetBase64.equals(stackBase64);
        }
        return !strictHash;
    }

    private ItemStack findSingleCategoryItem(Player player, SpecialCategory category) {
        if (player == null || category == null) {
            return null;
        }
        Map<String, ItemStack> distinct = new LinkedHashMap<String, ItemStack>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SlotRemovalPlan.STORAGE_SLOT_COUNT; ++slot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR || MarketGuiItem.isMarked(stack)) {
                continue;
            }
            if (SpecialCategory.of(stack) != category) {
                continue;
            }
            ItemStack single = stack.clone();
            single.setAmount(1);
            String base64 = ItemSerializer.itemToBase64(single);
            if (base64 == null) {
                continue;
            }
            distinct.putIfAbsent(base64, single);
            if (distinct.size() > 1) {
                return null;
            }
        }
        return distinct.isEmpty() ? null : distinct.values().iterator().next();
    }

    private int countCategoryItems(Player player, SpecialCategory category) {
        if (player == null || category == null) {
            return 0;
        }
        int total = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SlotRemovalPlan.STORAGE_SLOT_COUNT; ++slot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR || MarketGuiItem.isMarked(stack)) {
                continue;
            }
            if (SpecialCategory.of(stack) == category) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public synchronized String cancelOrder(Player player, int orderId) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u53d6\u6d88\u8ba2\u5355\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        Order order = this.plugin.getStorageManager().getOrder(orderId);
        if (order == null) {
            return "\u00a7c\u8ba2\u5355\u4e0d\u5b58\u5728\u3002";
        }
        if (!order.getPlayerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission("exchange.admin")) {
            return "\u00a7c\u8fd9\u4e0d\u662f\u4f60\u7684\u8ba2\u5355\u3002";
        }
        if (!order.isActive()) {
            return "\u00a7c\u8ba2\u5355\u5df2\u7ed3\u675f\uff0c\u65e0\u6cd5\u53d6\u6d88\u3002";
        }
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (!this.isValidSellEscrow(order, escrow)) {
                this.plugin.getLogger().severe("[AssetAudit] SELL_CANCEL_BLOCKED player=" + player.getUniqueId()
                    + " order=" + orderId + " remaining=" + order.getRemainingQty()
                    + " escrow=" + (escrow == null ? "missing" : escrow.getQuantity()));
                return "\u00a7c\u8be5\u5356\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
        } else {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            BigDecimal required = order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQty()));
            if (!this.isValidMoneyEscrow(order, escrow, required)) {
                this.plugin.getLogger().severe("[AssetAudit] BUY_CANCEL_BLOCKED player=" + player.getUniqueId()
                    + " order=" + orderId + " remaining=" + order.getRemainingQty()
                    + " escrow=" + (escrow == null ? "missing" : escrow.getAmount()));
                return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
        }
        Order cancelledOrder = this.copyOrder(order);
        cancelledOrder.setStatus(Order.OrderStatus.CANCELLED);
        if (!this.plugin.getStorageManager().updateOrder(cancelledOrder)) {
            return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        if (!this.returnEscrow(cancelledOrder)) {
            this.plugin.getStorageManager().updateOrder(order);
            return "\u00a7c\u8d44\u4ea7\u9000\u56de\u5931\u8d25\uff0c\u8ba2\u5355\u5df2\u6062\u590d\u4e3a\u6d3b\u8dc3\u72b6\u6001\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        if (order.getOrderType() == Order.OrderType.SELL) {
            this.plugin.getLogger().info("[AssetAudit] SELL_CANCEL player=" + player.getUniqueId()
                + " order=" + orderId + " item=" + order.getItemId() + " refund=" + order.getRemainingQty());
            this.refreshLowestSellStatus(order.getItemId());
        }
        return "\u00a7a\u8ba2\u5355 #" + orderId + " \u5df2\u53d6\u6d88\uff0c\u8d44\u4ea7\u5df2\u9000\u56de\u3002";
    }

    public synchronized String withdrawOrderQuantity(Player player, int orderId, int quantity) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u53d6\u56de\u6302\u5355\u3002";
        }
        if (quantity <= 0) {
            return "\u00a7c\u53d6\u56de\u6570\u91cf\u5fc5\u987b\u5927\u4e8e 0\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(player)) {
            return this.plugin.growthAccessMessage(player);
        }
        Order order = this.plugin.getStorageManager().getOrder(orderId);
        if (order == null) {
            return "\u00a7c\u8ba2\u5355\u4e0d\u5b58\u5728\u3002";
        }
        if (!order.getPlayerUuid().equals(player.getUniqueId().toString()) && !player.hasPermission("exchange.admin")) {
            return "\u00a7c\u8fd9\u4e0d\u662f\u4f60\u7684\u8ba2\u5355\u3002";
        }
        if (!order.isActive()) {
            return "\u00a7c\u8ba2\u5355\u5df2\u7ed3\u675f\uff0c\u65e0\u6cd5\u53d6\u56de\u3002";
        }
        int remaining = order.getRemainingQty();
        if (remaining <= 0) {
            return "\u00a7c\u8ba2\u5355\u6ca1\u6709\u53ef\u53d6\u56de\u7684\u8d44\u4ea7\u3002";
        }
        int withdraw = Math.min(quantity, remaining);
        if (withdraw >= remaining) {
            return this.cancelOrder(player, orderId);
        }
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (!this.isValidSellEscrow(order, escrow)) {
                this.plugin.getLogger().severe("[AssetAudit] SELL_PARTIAL_WITHDRAW_BLOCKED player=" + player.getUniqueId()
                    + " order=" + orderId + " remaining=" + remaining
                    + " escrow=" + (escrow == null ? "missing" : escrow.getQuantity()));
                return "\u00a7c\u8be5\u5356\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
            Order next = this.copyOrder(order);
            next.setQuantity(order.getQuantity() - withdraw);
            if (!this.plugin.getStorageManager().updateOrder(next)) {
                return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            EscrowEntry nextEscrow = this.copyEscrow(escrow);
            nextEscrow.setQuantity(escrow.getQuantity() - withdraw);
            if (!this.plugin.getStorageManager().insertEscrow(nextEscrow)
                || !this.deliverMatchedItems(this.parseUuid(order.getPlayerUuid()), escrow.getItemBase64(), withdraw)) {
                this.plugin.getStorageManager().updateOrder(order);
                this.restoreEscrowState(escrow);
                return "\u00a7c\u53d6\u56de\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            this.plugin.getLogger().info("[AssetAudit] SELL_PARTIAL_WITHDRAW player=" + player.getUniqueId()
                + " order=" + orderId + " item=" + order.getItemId() + " refund=" + withdraw);
            return "\u00a7a\u5df2\u53d6\u56de " + withdraw + " \u4e2a\u7269\u54c1\uff0c\u5356\u5355 #" + orderId
                + " \u5269\u4f59 " + (remaining - withdraw) + " \u4e2a\u3002";
        }
        BigDecimal refund = order.getPrice().multiply(BigDecimal.valueOf(withdraw));
        BigDecimal required = order.getPrice().multiply(BigDecimal.valueOf(remaining));
        EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
        if (!this.isValidMoneyEscrow(order, escrow, required)) {
            this.plugin.getLogger().severe("[AssetAudit] BUY_PARTIAL_WITHDRAW_BLOCKED player=" + player.getUniqueId()
                + " order=" + orderId + " remaining=" + remaining
                + " escrow=" + (escrow == null ? "missing" : escrow.getAmount()));
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
        }
        Order next = this.copyOrder(order);
        next.setQuantity(order.getQuantity() - withdraw);
        if (!this.plugin.getStorageManager().updateOrder(next)) {
            return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        EscrowEntry nextEscrow = this.copyEscrow(escrow);
        nextEscrow.setAmount(escrow.getAmount().subtract(refund));
        if (!this.plugin.getStorageManager().insertEscrow(nextEscrow)
            || !this.deliverRefundedMoney(this.parseUuid(order.getPlayerUuid()), refund)) {
            this.plugin.getStorageManager().updateOrder(order);
            this.restoreEscrowState(escrow);
            return "\u00a7c\u53d6\u56de\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        this.plugin.getLogger().info("[AssetAudit] BUY_PARTIAL_WITHDRAW player=" + player.getUniqueId()
            + " order=" + orderId + " item=" + order.getItemId() + " refund=" + refund.toPlainString());
        return "\u00a7a\u5df2\u51cf\u5c11 " + withdraw + " \u4e2a\u6c42\u8d2d\uff0c\u9000\u56de " + refund.toPlainString()
            + " " + this.plugin.getCurrencyName() + "\uff0c\u6c42\u8d2d\u5355 #" + orderId
            + " \u5269\u4f59 " + (remaining - withdraw) + " \u4e2a\u3002";
    }

    // ===================== 网页导出接口（WebMarketManager 调用） =====================
    // 与游戏内操作共用同一撮合/托管/结算引擎；资产来源与退回一律走个人仓库，
    // 检测（成长等级、数量、价格、停牌、持仓/余额、自成交、托管一致性）与游戏内完全一致。

    public synchronized String webPlaceSell(
        String uuid, String name, ExchangeItem exchangeItem, BigDecimal price, int quantity
    ) {
        if (exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u5546\u54c1\u3002";
        }
        if (this.plugin.getItemManager().getSpecialCategory(exchangeItem) != null) {
            return "\u00a7c\u300c" + exchangeItem.getDisplayName()
                + "\u300d\u4e3a\u7279\u6b8a\u7c7b\u522b\uff0c\u8bf7\u4f7f\u7528\u201c\u6307\u5b9a\u7269\u54c1\u4e0a\u67b6\u201d\u63a5\u53e3"
                + "\uff08\u643a\u5e26\u5177\u4f53\u7269\u54c1 base64\uff09\uff0c\u6216\u4f7f\u7528\u5feb\u901f\u4e0a\u67b6/\u4e00\u952e\u4f9b\u8d27\u3002";
        }
        ItemStack actualItem = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        return this.webPlaceSell(uuid, name, exchangeItem, actualItem, price, quantity);
    }

    public synchronized String webPlaceSell(
        String uuid, String name, ExchangeItem exchangeItem, ItemStack actualItem, BigDecimal price, int quantity
    ) {
        WebSellCreation creation = this.createWebSellOrderAndEscrow(
            uuid, name, exchangeItem, actualItem, price, quantity
        );
        if (creation.order() == null) {
            return creation.error();
        }
        Order order = creation.order();
        exchangeItem.setLastStockedAt(new Timestamp(System.currentTimeMillis()));
        exchangeItem.setLastEmptyAt(null);
        this.plugin.getStorageManager().updateExchangeItem(exchangeItem);
        this.plugin.getLogger().info("[WebMarket] SELL_CREATE player=" + uuid
            + " order=" + order.getId() + " item=" + exchangeItem.getId()
            + " removed=" + quantity + " escrow=" + order.getRemainingQty());
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        this.broadcastNewListing(exchangeItem);
        return "\u00a7a\u5356\u5355 #" + order.getId() + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: "
            + price + ", \u6570\u91cf: " + quantity;
    }

    private WebSellCreation createWebSellOrderAndEscrow(
        String uuid, String name, ExchangeItem exchangeItem, ItemStack actualItem, BigDecimal price, int quantity
    ) {
        if (uuid == null || exchangeItem == null) {
            return new WebSellCreation(null, "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002");
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return new WebSellCreation(null, this.plugin.growthAccessMessage(uuid));
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return new WebSellCreation(null, "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 "
                + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002");
        }
        if (!this.isPriceInConfiguredRange(price)) {
            return new WebSellCreation(null, "\u00a7c\u4ef7\u683c\u5fc5\u987b\u5728 "
                + this.plugin.getMinPrice() + " \u5230 " + this.plugin.getMaxPrice() + " \u4e4b\u95f4\u3002");
        }
        if (!this.isValidPriceTick(price)) {
            return new WebSellCreation(null, "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f "
                + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002");
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return new WebSellCreation(null, "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002");
        }
        ItemStack itemStack = actualItem != null ? actualItem : ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return new WebSellCreation(null, "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002");
        }
        String itemBase64 = ItemSerializer.itemToBase64(itemStack);
        if (itemBase64 == null) {
            return new WebSellCreation(null, "\u00a7c\u7269\u54c1\u5e8f\u5217\u5316\u5931\u8d25\u3002");
        }
        if (!this.plugin.getStorageManager().takeFromPlayerItemWarehouse(uuid, itemBase64, quantity)) {
            return new WebSellCreation(null, "\u00a7c\u4f60\u7684\u4ea4\u6613\u4ed3\u5e93\u4e2d\u6ca1\u6709\u8db3\u591f\u7684\u7269\u54c1\u3002");
        }
        Order order = new Order();
        order.setOrderType(Order.OrderType.SELL);
        order.setItemId(exchangeItem.getId());
        order.setPlayerUuid(uuid);
        order.setPlayerName(name == null ? "\u672a\u77e5\u73a9\u5bb6" : name);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(0);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            this.plugin.getStorageManager().addToPlayerItemWarehouse(uuid, itemBase64, quantity);
            return new WebSellCreation(null, "\u00a7c\u521b\u5efa\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002");
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(uuid);
        escrow.setAssetType(EscrowEntry.AssetType.ITEM);
        escrow.setAmount(BigDecimal.ZERO);
        escrow.setItemBase64(itemBase64);
        escrow.setQuantity(quantity);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)
            || !this.isValidSellEscrow(order, this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.ITEM))) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(order);
            this.plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.ITEM);
            this.plugin.getStorageManager().addToPlayerItemWarehouse(uuid, itemBase64, quantity);
            this.plugin.getLogger().severe("[WebMarket] SELL_CREATE_ABORT player=" + uuid
                + " order=" + orderId + " item=" + exchangeItem.getId()
                + " reason=escrow_verification_failed");
            return new WebSellCreation(null, "\u00a7c\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u7269\u54c1\u5df2\u9000\u56de\u4ed3\u5e93\u3002");
        }
        return new WebSellCreation(order, null);
    }

    public synchronized String webPlaceBuy(
        String uuid, String name, ExchangeItem exchangeItem, BigDecimal price, int quantity
    ) {
        if (uuid == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isPriceInConfiguredRange(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u5728 " + this.plugin.getMinPrice()
                + " \u5230 " + this.plugin.getMaxPrice() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isValidPriceTick(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002";
        }
        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal tax = TaxCalculator.tax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal chargedTotal = TaxCalculator.withTax(totalCost, this.plugin.getTaxRatePercent());
        if (this.plugin.getStorageManager().getMoneyWarehouseBalance(uuid).compareTo(chargedTotal) < 0) {
            return "\u00a7c\u8d27\u5e01\u4ed3\u5e93\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + chargedTotal
                + " \uff08\u542b\u4ea4\u6613\u7a0e " + tax + "\uff09\uff0c\u5f53\u524d: "
                + this.plugin.getStorageManager().getMoneyWarehouseBalance(uuid);
        }
        if (!this.plugin.getStorageManager().takeFromMoneyWarehouse(uuid, chargedTotal)) {
            return "\u00a7c\u4ed3\u5e93\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        Order order = new Order();
        order.setOrderType(Order.OrderType.BUY);
        order.setItemId(exchangeItem.getId());
        order.setPlayerUuid(uuid);
        order.setPlayerName(name == null ? "\u672a\u77e5\u73a9\u5bb6" : name);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(0);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        order.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(order);
        if (orderId <= 0) {
            this.plugin.getStorageManager().addToMoneyWarehouse(uuid, chargedTotal);
            return "\u00a7c\u521b\u5efa\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        order.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(uuid);
        escrow.setAssetType(EscrowEntry.AssetType.MONEY);
        escrow.setAmount(totalCost);
        escrow.setItemBase64(null);
        escrow.setQuantity(0);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)
            || !this.isValidMoneyEscrow(order, this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.MONEY), totalCost)) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(order);
            this.plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.MONEY);
            this.plugin.getStorageManager().addToMoneyWarehouse(uuid, chargedTotal);
            return "\u00a7c\u8ba2\u5355\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u5df2\u9000\u56de\u4ed3\u5e93\u6263\u9664\u7684\u661f\u5149\u70b9\u3002";
        }
        this.plugin.collectTax(tax);
        this.plugin.getLogger().info("[WebMarket] BUY_CREATE player=" + uuid
            + " order=" + orderId + " item=" + exchangeItem.getId()
            + " price=" + price + " qty=" + quantity);
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        this.broadcastNewBuyRequest(exchangeItem, price);
        return "\u00a7a\u4e70\u5355 #" + orderId + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: " + price
            + ", \u6570\u91cf: " + quantity + "\uff0c\u5546\u54c1\u603b\u4ef7: " + totalCost
            + "\uff0c\u4ea4\u6613\u7a0e: " + tax + "\uff0c\u5b9e\u9645\u6263\u6b3e: " + chargedTotal;
    }

    public synchronized String webDirectBuy(String uuid, String name, int sellOrderId, int quantity) {
        if (uuid == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u8d2d\u4e70\u7269\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        Order sellOrder = this.plugin.getStorageManager().getOrder(sellOrderId);
        if (sellOrder == null || sellOrder.getOrderType() != Order.OrderType.SELL || !sellOrder.isActive()) {
            return "\u00a7c\u8be5\u5356\u5355\u5df2\u4e0d\u53ef\u7528\u3002";
        }
        if (sellOrder.getPlayerUuid().equals(uuid)) {
            return "\u00a7c\u4e0d\u80fd\u8d2d\u4e70\u81ea\u5df1\u4e0a\u67b6\u7684\u7269\u54c1\u3002";
        }
        if (sellOrder.getRemainingQty() < quantity) {
            return "\u00a7c\u8be5\u5356\u5355\u5269\u4f59\u6570\u91cf\u4e0d\u8db3\u3002";
        }
        ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(sellOrder.getItemId());
        if (exchangeItem == null) {
            return "\u00a7c\u8be5\u5546\u54c1\u54c1\u79cd\u4e0d\u5b58\u5728\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u8d2d\u4e70\u3002";
        }
        BigDecimal totalCost = sellOrder.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal tax = TaxCalculator.tax(totalCost, this.plugin.getTaxRatePercent());
        BigDecimal chargedTotal = TaxCalculator.withTax(totalCost, this.plugin.getTaxRatePercent());
        if (this.plugin.getStorageManager().getMoneyWarehouseBalance(uuid).compareTo(chargedTotal) < 0) {
            return "\u00a7c\u8d27\u5e01\u4ed3\u5e93\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + chargedTotal
                + " \uff08\u542b\u4ea4\u6613\u7a0e " + tax + "\uff09\uff0c\u5f53\u524d: "
                + this.plugin.getStorageManager().getMoneyWarehouseBalance(uuid);
        }
        if (!this.plugin.getStorageManager().takeFromMoneyWarehouse(uuid, chargedTotal)) {
            return "\u00a7c\u4ed3\u5e93\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        Order buyOrder = new Order();
        buyOrder.setOrderType(Order.OrderType.BUY);
        buyOrder.setItemId(exchangeItem.getId());
        buyOrder.setPlayerUuid(uuid);
        buyOrder.setPlayerName(name == null ? "\u672a\u77e5\u73a9\u5bb6" : name);
        buyOrder.setPrice(sellOrder.getPrice());
        buyOrder.setQuantity(quantity);
        buyOrder.setFilledQty(0);
        buyOrder.setStatus(Order.OrderStatus.OPEN);
        buyOrder.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        buyOrder.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int orderId = this.plugin.getStorageManager().insertOrder(buyOrder);
        if (orderId <= 0) {
            this.plugin.getStorageManager().addToMoneyWarehouse(uuid, chargedTotal);
            return "\u00a7c\u521b\u5efa\u8d2d\u4e70\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        buyOrder.setId(orderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(uuid);
        escrow.setAssetType(EscrowEntry.AssetType.MONEY);
        escrow.setAmount(totalCost);
        escrow.setItemBase64(null);
        escrow.setQuantity(0);
        if (!this.plugin.getStorageManager().insertEscrow(escrow)
            || !this.isValidMoneyEscrow(buyOrder, this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.MONEY), totalCost)) {
            buyOrder.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(buyOrder);
            this.plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.MONEY);
            this.plugin.getStorageManager().addToMoneyWarehouse(uuid, chargedTotal);
            return "\u00a7c\u8d2d\u4e70\u8ba2\u5355\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u5df2\u9000\u56de\u4ed3\u5e93\u6263\u9664\u7684\u661f\u5149\u70b9\u3002";
        }
        this.plugin.collectTax(tax);
        if (!this.executeMatch(buyOrder, sellOrder, quantity)) {
            String cancelResult = this.webCancel(uuid, false, orderId);
            return "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002 " + cancelResult;
        }
        return "\u00a7a\u5df2\u8d2d\u4e70 " + exchangeItem.getDisplayName() + " x" + quantity
            + "\uff0c\u6210\u4ea4\u4ef7: " + sellOrder.getPrice()
            + "\uff0c\u4ea4\u6613\u7a0e: " + tax + "\uff0c\u5b9e\u9645\u6263\u6b3e: " + chargedTotal;
    }

    public synchronized String webDirectSell(String uuid, String name, int buyOrderId, int quantity) {
        if (uuid == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u51fa\u552e\u7269\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        Order buyOrder = this.plugin.getStorageManager().getOrder(buyOrderId);
        if (buyOrder == null || buyOrder.getOrderType() != Order.OrderType.BUY || !buyOrder.isActive()) {
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u5df2\u4e0d\u53ef\u7528\u3002";
        }
        if (buyOrder.getPlayerUuid().equals(uuid)) {
            return "\u00a7c\u4e0d\u80fd\u51fa\u552e\u7ed9\u81ea\u5df1\u7684\u6c42\u8d2d\u5355\u3002";
        }
        if (buyOrder.getRemainingQty() < quantity) {
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u5269\u4f59\u6570\u91cf\u4e0d\u8db3\u3002";
        }
        ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(buyOrder.getItemId());
        if (exchangeItem == null) {
            return "\u00a7c\u8be5\u5546\u54c1\u54c1\u79cd\u4e0d\u5b58\u5728\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u4f9b\u8d27\u3002";
        }
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (category != null) {
            return this.webSupplyCategoryToBuyOrder(uuid, name, buyOrder, exchangeItem, quantity);
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        WebSellCreation creation = this.createWebSellOrderAndEscrow(
            uuid, name, exchangeItem, itemStack, buyOrder.getPrice(), quantity
        );
        if (creation.order() == null) {
            return creation.error();
        }
        Order sellOrder = creation.order();
        this.plugin.getLogger().info("[WebMarket] SELL_TO_BUY player=" + uuid
            + " sellOrder=" + sellOrder.getId() + " buyOrder=" + buyOrderId
            + " item=" + exchangeItem.getId() + " removed=" + quantity);
        if (!this.executeMatch(buyOrder, sellOrder, quantity)) {
            String cancelResult = this.webCancel(uuid, false, sellOrder.getId());
            return "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u7269\u54c1\u672a\u6210\u4ea4\u3002 " + cancelResult;
        }
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5df2\u51fa\u552e " + exchangeItem.getDisplayName() + " x" + quantity
            + "\uff0c\u6210\u4ea4\u4ef7: " + buyOrder.getPrice();
    }

    private String webSupplyCategoryToBuyOrder(
        String uuid, String name, Order buyOrder, ExchangeItem exchangeItem, int quantity
    ) {
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (uuid == null || buyOrder == null || exchangeItem == null || category == null || quantity <= 0) {
            return "\u00a7c\u65e0\u6548\u7684\u8ba2\u5355\u6216\u6570\u91cf\u3002";
        }
        Map<String, Integer> warehouse = this.plugin.getStorageManager().getPlayerItemWarehouse(uuid);
        Map<String, Integer> distinct = new LinkedHashMap<String, Integer>();
        int total = 0;
        for (Map.Entry<String, Integer> entry : warehouse.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            ItemStack stack = ItemSerializer.itemFromBase64(entry.getKey());
            if (stack == null || SpecialCategory.of(stack) != category) {
                continue;
            }
            distinct.put(entry.getKey(), entry.getValue());
            total += entry.getValue();
        }
        if (total < quantity) {
            return "\u00a7c\u4ed3\u5e93\u4e2d\u7684\u300c" + category.displayName() + "\u300d\u7269\u54c1\u4e0d\u8db3 "
                + quantity + " \u4e2a\u3002";
        }
        int supplied = 0;
        int remaining = quantity;
        for (Map.Entry<String, Integer> entry : distinct.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(entry.getValue(), remaining);
            ItemStack variant = ItemSerializer.itemFromBase64(entry.getKey());
            if (variant == null) {
                continue;
            }
            int orderRemaining = take;
            while (orderRemaining > 0) {
                int chunk = Math.min(orderRemaining, this.plugin.getMaxOrderQuantity());
                WebSellCreation creation = this.createWebSellOrderAndEscrow(
                    uuid, name, exchangeItem, variant, buyOrder.getPrice(), chunk
                );
                if (creation.order() == null) {
                    return supplied > 0
                        ? "\u00a7a\u5df2\u4f9b\u8d27 " + supplied + " \u4e2a\uff0c\u540e\u7eed\u4ea4\u6613\u5931\u8d25\uff1a" + creation.error()
                        : creation.error();
                }
                this.plugin.getLogger().info("[WebMarket] SELL_TO_BUY player=" + uuid
                    + " sellOrder=" + creation.order().getId() + " buyOrder=" + buyOrder.getId()
                    + " item=" + exchangeItem.getId() + " removed=" + chunk);
                if (!this.executeMatch(buyOrder, creation.order(), chunk)) {
                    String cancelResult = this.webCancel(uuid, false, creation.order().getId());
                    return supplied > 0
                        ? "\u00a7a\u5df2\u4f9b\u8d27 " + supplied + " \u4e2a\uff0c\u540e\u7eed\u4ea4\u6613\u672a\u5b8c\u6210\u3002 " + cancelResult
                        : "\u00a7c\u4ea4\u6613\u672a\u5b8c\u6210\uff0c\u7269\u54c1\u672a\u6210\u4ea4\u3002 " + cancelResult;
                }
                supplied += chunk;
                orderRemaining -= chunk;
                remaining -= chunk;
            }
        }
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5df2\u51fa\u552e " + exchangeItem.getDisplayName() + " x" + supplied
            + "\uff0c\u6210\u4ea4\u4ef7: " + buyOrder.getPrice();
    }

    public synchronized String webQuickSell(String uuid, String name, ExchangeItem exchangeItem) {
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        BigDecimal lowestPrice = this.getLowestSellPrice(exchangeItem.getId());
        if (lowestPrice == null || lowestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
            if (status != null && status.getLastClose() != null && status.getLastClose().compareTo(BigDecimal.ZERO) > 0) {
                lowestPrice = status.getLastClose();
            }
        }
        if (lowestPrice == null || lowestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "\u00a7c\u5f53\u524d\u65e0\u53c2\u8003\u4ef7\u683c\uff0c\u8bf7\u4f7f\u7528\u666e\u901a\u4e0a\u67b6\u624b\u52a8\u8f93\u5165\u4ef7\u683c\u3002";
        }
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        if (category != null) {
            Map<String, Integer> entries = this.webWarehouseCategoryEntries(uuid, category);
            if (entries.isEmpty()) {
                return "\u00a7c\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u4e0a\u67b6\u7684\u300c" + category.displayName()
                    + "\u300d\u7269\u54c1\uff0c\u6216\u5305\u542b\u591a\u79cd\u540c\u7c7b\u7269\u54c1\uff0c\u8bf7\u4f7f\u7528\u4e0a\u67b6\u83dc\u5355\u3002";
            }
            if (entries.size() > 1) {
                return "\u00a7c\u4ed3\u5e93\u4e2d\u5305\u542b\u591a\u79cd\u540c\u7c7b\u7269\u54c1\uff0c\u8bf7\u4f7f\u7528\u4e0a\u67b6\u83dc\u5355\u9009\u62e9\u5177\u4f53\u7269\u54c1\u3002";
            }
            Map.Entry<String, Integer> entry = entries.entrySet().iterator().next();
            ItemStack actualItem = ItemSerializer.itemFromBase64(entry.getKey());
            if (actualItem == null) {
                return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
            }
            int quantity = Math.min(entry.getValue(), this.plugin.getMaxOrderQuantity());
            return this.webPlaceSell(uuid, name, exchangeItem, actualItem, lowestPrice, quantity);
        }
        int totalCount = this.webWarehouseQuantity(uuid, exchangeItem.getItemBase64());
        if (totalCount <= 0) {
            return "\u00a7c\u4ed3\u5e93\u4e2d\u6ca1\u6709\u8be5\u7c7b\u578b\u7269\u54c1\u3002";
        }
        int quantity = Math.min(totalCount, this.plugin.getMaxOrderQuantity());
        return this.webPlaceSell(uuid, name, exchangeItem, lowestPrice, quantity);
    }

    public synchronized SupplyPlanner.Plan webSupplyPlan(String uuid, ExchangeItem exchangeItem) {
        if (uuid == null || exchangeItem == null) {
            return SupplyPlanner.plan(0, null);
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return SupplyPlanner.plan(0, null);
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return SupplyPlanner.plan(0, null);
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return SupplyPlanner.plan(0, null);
        }
        List<Order> buyOrders = new ArrayList<Order>(
            this.getActiveOrders(exchangeItem.getId(), Order.OrderType.BUY)
        );
        buyOrders.removeIf(order -> uuid.equals(order.getPlayerUuid()));
        SpecialCategory category = this.plugin.getItemManager().getSpecialCategory(exchangeItem);
        int available;
        if (category != null) {
            int total = 0;
            for (Integer count : this.webWarehouseCategoryEntries(uuid, category).values()) {
                total += count;
            }
            available = total;
        } else {
            available = this.webWarehouseQuantity(uuid, exchangeItem.getItemBase64());
        }
        return SupplyPlanner.plan(available, buyOrders);
    }

    public synchronized String webSupplyAll(String uuid, String name, ExchangeItem exchangeItem) {
        if (uuid == null || exchangeItem == null) {
            return "\u00a7c\u65e0\u6548\u7684\u73a9\u5bb6\u6216\u5546\u54c1\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        SupplyPlanner.Plan plan = this.webSupplyPlan(uuid, exchangeItem);
        if (plan.matchedQuantity() <= 0) {
            if (plan.availableQuantity() > 0) {
                return "\u00a7c\u5f53\u524d\u6ca1\u6709\u5176\u4ed6\u73a9\u5bb6\u7684\u53ef\u4f9b\u8d27\u6c42\u8d2d\u5355\uff08\u4e0d\u80fd\u4f9b\u8d27\u7ed9\u81ea\u5df1\u7684\u6c42\u8d2d\u5355\uff09\u3002";
            }
            return "\u00a7c\u5f53\u524d\u6ca1\u6709\u53ef\u4f9b\u8d27\u7684\u6c42\u8d2d\u5355\uff0c\u6216\u4ed3\u5e93\u4e2d\u6ca1\u6709\u53ef\u7528\u7269\u54c1\u3002";
        }
        int supplied = 0;
        BigDecimal grossAmount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SupplyPlanner.Allocation allocation : plan.allocations()) {
            int remaining = allocation.quantity();
            boolean allocationFailed = false;
            while (remaining > 0) {
                int chunk = Math.min(remaining, this.plugin.getMaxOrderQuantity());
                String result = this.webDirectSell(uuid, name, allocation.order().getId(), chunk);
                if (result == null || result.startsWith("\u00a7c")) {
                    allocationFailed = true;
                    break;
                }
                supplied += chunk;
                BigDecimal amount = allocation.order().getPrice().multiply(BigDecimal.valueOf(chunk));
                grossAmount = grossAmount.add(amount);
                tax = tax.add(TaxCalculator.tax(amount, this.plugin.getTaxRatePercent()));
                remaining -= chunk;
            }
            if (allocationFailed) {
                break;
            }
        }
        if (supplied <= 0) {
            return "\u00a7c\u4e00\u952e\u4f9b\u8d27\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u4ed3\u5e93\u548c\u6c42\u8d2d\u5355\u3002";
        }
        BigDecimal received = grossAmount.subtract(tax);
        String completion = supplied == plan.matchedQuantity() ? "\u5b8c\u6210" : "\u90e8\u5206\u5b8c\u6210";
        return "\u00a7a\u4e00\u952e\u4f9b\u8d27" + completion + "\uff1a" + supplied + " \u4e2a"
            + "\uff0c\u9884\u8ba1\u6210\u4ea4\u989d: " + grossAmount.toPlainString()
            + "\uff0c\u9884\u8ba1\u5230\u8d26: " + received.toPlainString()
            + " \uff08\u4ea4\u6613\u7a0e " + tax.toPlainString() + "\uff09";
    }

    public synchronized String webCancel(String uuid, boolean admin, int orderId) {
        if (uuid == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u53d6\u6d88\u8ba2\u5355\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        Order order = this.plugin.getStorageManager().getOrder(orderId);
        if (order == null) {
            return "\u00a7c\u8ba2\u5355\u4e0d\u5b58\u5728\u3002";
        }
        if (!order.getPlayerUuid().equals(uuid) && !admin) {
            return "\u00a7c\u8fd9\u4e0d\u662f\u4f60\u7684\u8ba2\u5355\u3002";
        }
        if (!order.isActive()) {
            return "\u00a7c\u8ba2\u5355\u5df2\u7ed3\u675f\uff0c\u65e0\u6cd5\u53d6\u6d88\u3002";
        }
        String owner = order.getPlayerUuid();
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (!this.isValidSellEscrow(order, escrow)) {
                this.plugin.getLogger().severe("[WebMarket] SELL_CANCEL_BLOCKED player=" + uuid
                    + " order=" + orderId + " remaining=" + order.getRemainingQty()
                    + " escrow=" + (escrow == null ? "missing" : escrow.getQuantity()));
                return "\u00a7c\u8be5\u5356\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
            Order cancelledOrder = this.copyOrder(order);
            cancelledOrder.setStatus(Order.OrderStatus.CANCELLED);
            if (!this.plugin.getStorageManager().updateOrder(cancelledOrder)) {
                return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            if (!this.plugin.getStorageManager().addToPlayerItemWarehouse(
                owner, escrow.getItemBase64(), order.getRemainingQty())) {
                this.plugin.getStorageManager().updateOrder(order);
                return "\u00a7c\u8d44\u4ea7\u9000\u56de\u5931\u8d25\uff0c\u8ba2\u5355\u5df2\u6062\u590d\u4e3a\u6d3b\u8dc3\u72b6\u6001\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            this.plugin.getLogger().info("[WebMarket] SELL_CANCEL player=" + uuid
                + " order=" + orderId + " item=" + order.getItemId()
                + " refund=" + order.getRemainingQty());
            this.refreshLowestSellStatus(order.getItemId());
        } else {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            BigDecimal required = order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQty()));
            if (!this.isValidMoneyEscrow(order, escrow, required)) {
                this.plugin.getLogger().severe("[WebMarket] BUY_CANCEL_BLOCKED player=" + uuid
                    + " order=" + orderId + " remaining=" + order.getRemainingQty()
                    + " escrow=" + (escrow == null ? "missing" : escrow.getAmount()));
                return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
            Order cancelledOrder = this.copyOrder(order);
            cancelledOrder.setStatus(Order.OrderStatus.CANCELLED);
            if (!this.plugin.getStorageManager().updateOrder(cancelledOrder)) {
                return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            BigDecimal refund = escrow == null ? required : escrow.getAmount();
            if (!this.plugin.getStorageManager().addToMoneyWarehouse(owner, refund)) {
                this.plugin.getStorageManager().updateOrder(order);
                return "\u00a7c\u8d44\u4ea7\u9000\u56de\u5931\u8d25\uff0c\u8ba2\u5355\u5df2\u6062\u590d\u4e3a\u6d3b\u8dc3\u72b6\u6001\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            this.plugin.getLogger().info("[WebMarket] BUY_CANCEL player=" + uuid
                + " order=" + orderId + " refund=" + refund);
        }
        return "\u00a7a\u8ba2\u5355 #" + orderId + " \u5df2\u53d6\u6d88\uff0c\u8d44\u4ea7\u5df2\u9000\u56de\u4ed3\u5e93\u3002";
    }

    public synchronized String webWithdrawQuantity(String uuid, boolean admin, int orderId, int quantity) {
        if (uuid == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u53d6\u56de\u6302\u5355\u3002";
        }
        if (quantity <= 0) {
            return "\u00a7c\u53d6\u56de\u6570\u91cf\u5fc5\u987b\u5927\u4e8e 0\u3002";
        }
        if (this.plugin.isGrowthAccessRestricted(uuid)) {
            return this.plugin.growthAccessMessage(uuid);
        }
        Order order = this.plugin.getStorageManager().getOrder(orderId);
        if (order == null) {
            return "\u00a7c\u8ba2\u5355\u4e0d\u5b58\u5728\u3002";
        }
        if (!order.getPlayerUuid().equals(uuid) && !admin) {
            return "\u00a7c\u8fd9\u4e0d\u662f\u4f60\u7684\u8ba2\u5355\u3002";
        }
        if (!order.isActive()) {
            return "\u00a7c\u8ba2\u5355\u5df2\u7ed3\u675f\uff0c\u65e0\u6cd5\u53d6\u56de\u3002";
        }
        int remaining = order.getRemainingQty();
        if (remaining <= 0) {
            return "\u00a7c\u8ba2\u5355\u6ca1\u6709\u53ef\u53d6\u56de\u7684\u8d44\u4ea7\u3002";
        }
        int withdraw = Math.min(quantity, remaining);
        if (withdraw >= remaining) {
            return this.webCancel(uuid, admin, orderId);
        }
        String owner = order.getPlayerUuid();
        if (order.getOrderType() == Order.OrderType.SELL) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (!this.isValidSellEscrow(order, escrow)) {
                this.plugin.getLogger().severe("[WebMarket] SELL_PARTIAL_WITHDRAW_BLOCKED player=" + uuid
                    + " order=" + orderId + " remaining=" + remaining
                    + " escrow=" + (escrow == null ? "missing" : escrow.getQuantity()));
                return "\u00a7c\u8be5\u5356\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
            }
            Order next = this.copyOrder(order);
            next.setQuantity(order.getQuantity() - withdraw);
            if (!this.plugin.getStorageManager().updateOrder(next)) {
                return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            EscrowEntry nextEscrow = this.copyEscrow(escrow);
            nextEscrow.setQuantity(escrow.getQuantity() - withdraw);
            if (!this.plugin.getStorageManager().insertEscrow(nextEscrow)
                || !this.plugin.getStorageManager().addToPlayerItemWarehouse(owner, escrow.getItemBase64(), withdraw)) {
                this.plugin.getStorageManager().updateOrder(order);
                this.restoreEscrowState(escrow);
                return "\u00a7c\u53d6\u56de\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
            }
            this.plugin.getLogger().info("[WebMarket] SELL_PARTIAL_WITHDRAW player=" + uuid
                + " order=" + orderId + " item=" + order.getItemId() + " refund=" + withdraw);
            return "\u00a7a\u5df2\u53d6\u56de " + withdraw + " \u4e2a\u7269\u54c1\uff0c\u5356\u5355 #" + orderId
                + " \u5269\u4f59 " + (remaining - withdraw) + " \u4e2a\u3002";
        }
        BigDecimal refund = order.getPrice().multiply(BigDecimal.valueOf(withdraw));
        BigDecimal required = order.getPrice().multiply(BigDecimal.valueOf(remaining));
        EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
        if (!this.isValidMoneyEscrow(order, escrow, required)) {
            this.plugin.getLogger().severe("[WebMarket] BUY_PARTIAL_WITHDRAW_BLOCKED player=" + uuid
                + " order=" + orderId + " remaining=" + remaining
                + " escrow=" + (escrow == null ? "missing" : escrow.getAmount()));
            return "\u00a7c\u8be5\u6c42\u8d2d\u5355\u7684\u6258\u7ba1\u6570\u636e\u5f02\u5e38\uff0c\u5df2\u963b\u6b62\u9000\u6b3e\u5e76\u8bb0\u5f55\u65e5\u5fd7\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
        }
        Order next = this.copyOrder(order);
        next.setQuantity(order.getQuantity() - withdraw);
        if (!this.plugin.getStorageManager().updateOrder(next)) {
            return "\u00a7c\u8ba2\u5355\u72b6\u6001\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        EscrowEntry nextEscrow = this.copyEscrow(escrow);
        nextEscrow.setAmount(escrow.getAmount().subtract(refund));
        if (!this.plugin.getStorageManager().insertEscrow(nextEscrow)
            || !this.plugin.getStorageManager().addToMoneyWarehouse(owner, refund)) {
            this.plugin.getStorageManager().updateOrder(order);
            this.restoreEscrowState(escrow);
            return "\u00a7c\u53d6\u56de\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
        }
        this.plugin.getLogger().info("[WebMarket] BUY_PARTIAL_WITHDRAW player=" + uuid
            + " order=" + orderId + " item=" + order.getItemId() + " refund=" + refund.toPlainString());
        return "\u00a7a\u5df2\u51cf\u5c11 " + withdraw + " \u4e2a\u6c42\u8d2d\uff0c\u9000\u56de " + refund.toPlainString()
            + " " + this.plugin.getCurrencyName() + "\uff0c\u6c42\u8d2d\u5355 #" + orderId
            + " \u5269\u4f59 " + (remaining - withdraw) + " \u4e2a\u3002";
    }

    private int webWarehouseQuantity(String uuid, String itemBase64) {
        if (uuid == null || itemBase64 == null) {
            return 0;
        }
        Integer qty = this.plugin.getStorageManager().getPlayerItemWarehouse(uuid).get(itemBase64);
        return qty == null ? 0 : qty;
    }

    private Map<String, Integer> webWarehouseCategoryEntries(String uuid, SpecialCategory category) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (uuid == null || category == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : this.plugin.getStorageManager().getPlayerItemWarehouse(uuid).entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            ItemStack stack = ItemSerializer.itemFromBase64(entry.getKey());
            if (stack != null && SpecialCategory.of(stack) == category) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private void matchOrder(Order newOrder) {
        if (newOrder == null || !newOrder.isActive()) {
            return;
        }
        if (newOrder.getOrderType() == Order.OrderType.BUY) {
            List<Order> sellOrders = this.plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.SELL);
            for (Order sellOrder : sellOrders) {
                if (!newOrder.isActive() || sellOrder == null || !sellOrder.isActive()) {
                    if (!newOrder.isActive()) {
                        break;
                    }
                    continue;
                }
                if (this.isSamePlayer(newOrder, sellOrder)
                    || sellOrder.getPrice().compareTo(newOrder.getPrice()) > 0) {
                    if (sellOrder.getPrice().compareTo(newOrder.getPrice()) > 0) {
                        break;
                    }
                    continue;
                }
                int matchQty = Math.min(newOrder.getRemainingQty(), sellOrder.getRemainingQty());
                if (matchQty <= 0) {
                    continue;
                }
                this.executeMatch(newOrder, sellOrder, matchQty);
            }
        } else {
            List<Order> buyOrders = this.plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.BUY);
            for (Order buyOrder : buyOrders) {
                if (!newOrder.isActive() || buyOrder == null || !buyOrder.isActive()) {
                    if (!newOrder.isActive()) {
                        break;
                    }
                    continue;
                }
                if (this.isSamePlayer(buyOrder, newOrder)
                    || buyOrder.getPrice().compareTo(newOrder.getPrice()) < 0) {
                    if (buyOrder.getPrice().compareTo(newOrder.getPrice()) < 0) {
                        break;
                    }
                    continue;
                }
                int matchQty = Math.min(newOrder.getRemainingQty(), buyOrder.getRemainingQty());
                if (matchQty <= 0) {
                    continue;
                }
                this.executeMatch(buyOrder, newOrder, matchQty);
            }
        }
    }

    private boolean executeMatch(Order buyOrder, Order sellOrder, int quantity) {
        if (buyOrder == null || sellOrder == null
            || !buyOrder.isActive()
            || !sellOrder.isActive()
            || buyOrder.getOrderType() != Order.OrderType.BUY
            || sellOrder.getOrderType() != Order.OrderType.SELL
            || buyOrder.getItemId() != sellOrder.getItemId()
            || quantity <= 0
            || quantity > buyOrder.getRemainingQty()
            || quantity > sellOrder.getRemainingQty()
            || buyOrder.getPrice() == null
            || sellOrder.getPrice() == null
            || buyOrder.getPrice().compareTo(BigDecimal.ZERO) <= 0
            || sellOrder.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (this.isSamePlayer(buyOrder, sellOrder)) {
            this.plugin.getLogger().warning("[AssetAudit] MATCH_BLOCKED buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=self_trade");
            return false;
        }
        EscrowEntry buyerEscrow = this.plugin.getStorageManager()
            .getEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
        EscrowEntry sellerEscrow = this.plugin.getStorageManager()
            .getEscrow(sellOrder.getId(), EscrowEntry.AssetType.ITEM);
        BigDecimal tradePrice = this.resolveTradePrice(buyOrder, sellOrder);
        BigDecimal matchedMoney = tradePrice.multiply(BigDecimal.valueOf(quantity));
        if (!this.isValidMoneyEscrow(buyOrder, buyerEscrow, matchedMoney)
            || !this.isValidSellEscrow(sellOrder, sellerEscrow)
            || sellerEscrow.getQuantity() < quantity) {
            this.plugin.getLogger().severe("[AssetAudit] MATCH_BLOCKED buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " quantity=" + quantity
                + " reason=escrow_verification_failed");
            return false;
        }
        String deliveredItemBase64 = sellerEscrow.getItemBase64();
        if (deliveredItemBase64 == null || deliveredItemBase64.isEmpty()
            || ItemSerializer.itemFromBase64(deliveredItemBase64) == null) {
            this.plugin.getLogger().severe("[AssetAudit] MATCH_BLOCKED buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=item_deserialization_failed");
            return false;
        }

        BigDecimal lowestSellBeforeTrade = this.getLowestSellPrice(buyOrder.getItemId());
        BigDecimal totalAmount = matchedMoney;
        BigDecimal buyerFee = BigDecimal.ZERO.setScale(2);
        BigDecimal sellerFee = this.calculateSellerFee(totalAmount);
        BigDecimal sellerReceives = totalAmount.subtract(sellerFee);
        UUID buyerUuid;
        UUID sellerUuid;
        try {
            buyerUuid = UUID.fromString(buyOrder.getPlayerUuid());
            sellerUuid = UUID.fromString(sellOrder.getPlayerUuid());
        }
        catch (IllegalArgumentException ex) {
            this.plugin.getLogger().severe("[AssetAudit] MATCH_BLOCKED buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=invalid_player_uuid");
            return false;
        }

        EscrowEntry nextBuyerEscrow = this.copyEscrow(buyerEscrow);
        nextBuyerEscrow.setAmount(buyerEscrow.getAmount().subtract(matchedMoney));
        EscrowEntry nextSellerEscrow = this.copyEscrow(sellerEscrow);
        nextSellerEscrow.setQuantity(sellerEscrow.getQuantity() - quantity);
        if (!this.writeEscrowState(buyerEscrow, nextBuyerEscrow)
            || !this.writeEscrowState(sellerEscrow, nextSellerEscrow)) {
            this.restoreEscrowState(buyerEscrow);
            this.restoreEscrowState(sellerEscrow);
            this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=escrow_update_failed");
            return false;
        }

        Order previousBuyOrder = this.copyOrder(buyOrder);
        Order previousSellOrder = this.copyOrder(sellOrder);
        Order settledBuyOrder = this.copyOrder(buyOrder);
        Order settledSellOrder = this.copyOrder(sellOrder);
        settledBuyOrder.setFilledQty(buyOrder.getFilledQty() + quantity);
        settledSellOrder.setFilledQty(sellOrder.getFilledQty() + quantity);
        settledBuyOrder.setStatus(settledBuyOrder.getFilledQty() >= settledBuyOrder.getQuantity()
            ? Order.OrderStatus.CLOSED : Order.OrderStatus.PARTIAL);
        settledSellOrder.setStatus(settledSellOrder.getFilledQty() >= settledSellOrder.getQuantity()
            ? Order.OrderStatus.CLOSED : Order.OrderStatus.PARTIAL);

        BigDecimal releasedReserve = BigDecimal.ZERO;
        if (settledBuyOrder.getStatus() == Order.OrderStatus.CLOSED
            && nextBuyerEscrow.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            releasedReserve = nextBuyerEscrow.getAmount();
            boolean reserveAdded = this.plugin.getStorageManager()
                .addToMoneyWarehouse(buyOrder.getPlayerUuid(), releasedReserve);
            boolean escrowDeleted = reserveAdded
                && this.plugin.getStorageManager().deleteEscrow(
                    buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            if (!escrowDeleted) {
                if (reserveAdded && !this.plugin.getStorageManager()
                    .takeFromMoneyWarehouse(buyOrder.getPlayerUuid(), releasedReserve)) {
                    this.plugin.getLogger().severe("[AssetAudit] RESERVE_RELEASE_ROLLBACK_FAILED buyOrder="
                        + buyOrder.getId() + " amount=" + releasedReserve);
                }
                this.restoreEscrowState(buyerEscrow);
                this.restoreEscrowState(sellerEscrow);
                this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                    + " sellOrder=" + sellOrder.getId() + " reason=reserve_release_failed");
                return false;
            }
        }

        if (!this.plugin.getStorageManager().updateOrder(settledBuyOrder)
            || !this.plugin.getStorageManager().updateOrder(settledSellOrder)) {
            this.rollbackMatchState(
                previousBuyOrder, previousSellOrder, buyerEscrow, sellerEscrow,
                releasedReserve, null, null
            );
            this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=order_update_failed");
            return false;
        }

        Trade trade = this.recordTrade(buyOrder.getItemId(), buyOrder.getPlayerUuid(),
            sellOrder.getPlayerUuid(), tradePrice, quantity, totalAmount, buyerFee, sellerFee,
            buyOrder.getId(), sellOrder.getId());
        if (trade == null) {
            this.rollbackMatchState(
                previousBuyOrder, previousSellOrder, buyerEscrow, sellerEscrow,
                releasedReserve, null, null
            );
            this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=trade_record_failed");
            return false;
        }

        MoneyDeliveryReceipt moneyDelivery = null;
        try {
            moneyDelivery = this.deliverMatchedMoney(sellerUuid, sellerReceives);
            if (moneyDelivery == null || !this.deliverMatchedItems(buyerUuid, deliveredItemBase64, quantity)) {
                this.rollbackMatchState(
                    previousBuyOrder, previousSellOrder, buyerEscrow, sellerEscrow,
                    releasedReserve, trade, moneyDelivery
                );
                this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                    + " sellOrder=" + sellOrder.getId() + " reason=delivery_failed");
                return false;
            }
        }
        catch (Throwable throwable) {
            this.rollbackMatchState(
                previousBuyOrder, previousSellOrder, buyerEscrow, sellerEscrow,
                releasedReserve, trade, moneyDelivery
            );
            this.plugin.getLogger().severe("[AssetAudit] MATCH_ABORT buyOrder=" + buyOrder.getId()
                + " sellOrder=" + sellOrder.getId() + " reason=delivery_exception: "
                + throwable.getClass().getSimpleName());
            return false;
        }

        this.plugin.collectTax(sellerFee);
        this.updateItemStatusAfterTrade(buyOrder.getItemId(), tradePrice, quantity);
        this.refreshLowestSellStatus(buyOrder.getItemId(), lowestSellBeforeTrade);
        SellBuyerTracker sellBuyerTracker = this.plugin.getSellBuyerTracker();
        if (sellBuyerTracker != null) {
            sellBuyerTracker.recordNewBuyer(sellerUuid, sellOrder.getPlayerName(), buyerUuid);
        }
        this.copySettledOrder(buyOrder, settledBuyOrder);
        this.copySettledOrder(sellOrder, settledSellOrder);
        this.notifyMatchParties(buyOrder, sellOrder, deliveredItemBase64, quantity, sellerReceives);
        return true;
    }

    private void notifyMatchParties(Order buyOrder, Order sellOrder, String itemBase64, int quantity,
                                    BigDecimal sellerReceives) {
        try {
            String itemName = "#" + buyOrder.getItemId();
            ItemStack item = ItemSerializer.itemFromBase64(itemBase64);
            if (item != null) {
                itemName = ItemDisplayNames.resolve(item);
            }
            UUID buyerUuid = this.parseUuid(buyOrder.getPlayerUuid());
            UUID sellerUuid = this.parseUuid(sellOrder.getPlayerUuid());
            if (buyerUuid != null) {
                this.plugin.getTradeNoticeBuffer().passiveArrived(buyerUuid, itemName, quantity);
            }
            if (sellerUuid != null) {
                this.plugin.getTradeNoticeBuffer().passiveSold(
                    sellerUuid, buyerUuid, itemName, quantity, sellerReceives);
            }
        }
        catch (Throwable throwable) {
            this.plugin.getLogger().warning("[Notifier] passive notice failed: " + throwable.getMessage());
        }
    }

    private boolean deliverMatchedItems(UUID buyerUuid, String itemBase64, int quantity) {
        if (buyerUuid == null || itemBase64 == null || itemBase64.isEmpty() || quantity <= 0) {
            return false;
        }
        String playerUuid = buyerUuid.toString();
        if (!this.plugin.getStorageManager().addToPlayerItemWarehouse(playerUuid, itemBase64, quantity)) {
            return false;
        }
        Player buyer = Bukkit.getPlayer(buyerUuid);
        if (buyer != null && buyer.isOnline()) {
            this.plugin.getStorageManager().withdrawWarehouseItem(buyer, itemBase64);
        }
        return true;
    }

    private MoneyDeliveryReceipt deliverMatchedMoney(UUID sellerUuid, BigDecimal amount) {
        if (sellerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return new MoneyDeliveryReceipt(sellerUuid, amount, false);
        }
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller != null && seller.isOnline() && EconomyUtil.deposit(sellerUuid, amount)) {
            return new MoneyDeliveryReceipt(sellerUuid, amount, true);
        }
        if (this.plugin.getStorageManager().addToMoneyWarehouse(sellerUuid.toString(), amount)) {
            return new MoneyDeliveryReceipt(sellerUuid, amount, false);
        }
        return null;
    }

    private Trade recordTrade(int itemId, String buyerUuid, String sellerUuid, BigDecimal price, int quantity,
                              BigDecimal totalAmount, BigDecimal buyerFee, BigDecimal sellerFee,
                              int buyOrderId, int sellOrderId) {
        Trade trade = new Trade();
        trade.setItemId(itemId);
        trade.setBuyerUuid(buyerUuid);
        trade.setSellerUuid(sellerUuid);
        trade.setPrice(price);
        trade.setQuantity(quantity);
        trade.setTotalAmount(totalAmount);
        trade.setBuyerFee(buyerFee);
        trade.setSellerFee(sellerFee);
        trade.setBuyOrderId(buyOrderId);
        trade.setSellOrderId(sellOrderId);
        trade.setTradedAt(new Timestamp(System.currentTimeMillis()));
        return this.plugin.getStorageManager().insertTrade(trade) > 0 ? trade : null;
    }

    private void rollbackMatchState(Order previousBuyOrder, Order previousSellOrder,
                                    EscrowEntry buyerEscrow, EscrowEntry sellerEscrow,
                                    BigDecimal releasedReserve, Trade trade,
                                    MoneyDeliveryReceipt moneyDelivery) {
        if (moneyDelivery != null && !this.rollbackMoneyDelivery(moneyDelivery)) {
            this.plugin.getLogger().severe("[AssetAudit] MONEY_DELIVERY_ROLLBACK_FAILED seller="
                + moneyDelivery.playerUuid + " amount=" + moneyDelivery.amount);
        }
        if (trade != null && !this.plugin.getStorageManager().deleteTrade(trade.getId())) {
            this.plugin.getLogger().severe("[AssetAudit] TRADE_RECORD_ROLLBACK_FAILED trade=" + trade.getId());
        }
        if (previousBuyOrder != null
            && !this.plugin.getStorageManager().updateOrder(previousBuyOrder)) {
            this.plugin.getLogger().severe("[AssetAudit] BUY_ORDER_ROLLBACK_FAILED order="
                + previousBuyOrder.getId());
        }
        if (previousSellOrder != null
            && !this.plugin.getStorageManager().updateOrder(previousSellOrder)) {
            this.plugin.getLogger().severe("[AssetAudit] SELL_ORDER_ROLLBACK_FAILED order="
                + previousSellOrder.getId());
        }
        if (releasedReserve != null && releasedReserve.compareTo(BigDecimal.ZERO) > 0
            && !this.plugin.getStorageManager().takeFromMoneyWarehouse(
                previousBuyOrder.getPlayerUuid(), releasedReserve)) {
            this.plugin.getLogger().severe("[AssetAudit] RESERVE_ROLLBACK_FAILED buyOrder="
                + previousBuyOrder.getId() + " amount=" + releasedReserve);
        }
        this.restoreEscrowState(buyerEscrow);
        this.restoreEscrowState(sellerEscrow);
    }

    private boolean rollbackMoneyDelivery(MoneyDeliveryReceipt delivery) {
        if (delivery.amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (delivery.directDeposit) {
            return EconomyUtil.withdraw(delivery.playerUuid, delivery.amount);
        }
        return this.plugin.getStorageManager().takeFromMoneyWarehouse(
            delivery.playerUuid.toString(), delivery.amount);
    }

    private BigDecimal resolveTradePrice(Order buyOrder, Order sellOrder) {
        Timestamp buyCreated = buyOrder.getCreatedAt();
        Timestamp sellCreated = sellOrder.getCreatedAt();
        if (buyCreated == null && sellCreated == null) {
            return buyOrder.getPrice();
        }
        if (buyCreated == null) {
            return sellOrder.getPrice();
        }
        if (sellCreated == null || buyCreated.before(sellCreated)) {
            return buyOrder.getPrice();
        }
        return sellOrder.getPrice();
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isSamePlayer(Order first, Order second) {
        if (first == null || second == null
            || first.getPlayerUuid() == null || second.getPlayerUuid() == null) {
            return false;
        }
        return first.getPlayerUuid().equalsIgnoreCase(second.getPlayerUuid());
    }

    private void copySettledOrder(Order target, Order settled) {
        if (target == null || settled == null) {
            return;
        }
        target.setFilledQty(settled.getFilledQty());
        target.setStatus(settled.getStatus());
        target.setUpdatedAt(settled.getUpdatedAt());
    }

    private BigDecimal calculateSellerFee(BigDecimal totalAmount) {
        return TaxCalculator.tax(totalAmount, this.plugin.getTaxRatePercent());
    }

    private void updateItemStatusAfterTrade(int itemId, BigDecimal price, int quantity) {
        ItemStatus status = this.plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            status = new ItemStatus();
            status.setItemId(itemId);
            status.setSuspended(false);
            status.setLastClose(price);
            status.setLastOpen(price);
            status.setHighToday(price);
            status.setLowToday(price);
            status.setVolumeToday(quantity);
        } else {
            if (status.getLastOpen().compareTo(BigDecimal.ZERO) == 0) {
                status.setLastOpen(price);
            }
            if (status.getHighToday().compareTo(price) < 0) {
                status.setHighToday(price);
            }
            if (status.getLowToday().compareTo(BigDecimal.ZERO) == 0 || status.getLowToday().compareTo(price) > 0) {
                status.setLowToday(price);
            }
            status.setVolumeToday(status.getVolumeToday() + quantity);
            status.setLastClose(price);
        }
        this.plugin.getItemManager().updateItemStatus(status);
    }

    private void refreshLowestSellStatus(int itemId) {
        this.refreshLowestSellStatus(itemId, null);
    }

    private void refreshLowestSellStatus(int itemId, BigDecimal fallbackReference) {
        ItemStatus status = this.plugin.getItemManager().getItemStatus(itemId);
        if (status == null) {
            status = new ItemStatus();
            status.setItemId(itemId);
            status.setSuspended(false);
            status.setLastClose(BigDecimal.ZERO);
            status.setLastOpen(BigDecimal.ZERO);
            status.setHighToday(BigDecimal.ZERO);
            status.setLowToday(BigDecimal.ZERO);
            status.setVolumeToday(0);
        }
        BigDecimal currentLowest = this.getLowestSellPrice(itemId);
        BigDecimal normalizedCurrent = currentLowest != null && currentLowest.compareTo(BigDecimal.ZERO) > 0 ? currentLowest : BigDecimal.ZERO;
        BigDecimal referenceLowest = status.getLowestSellReference() != null ? status.getLowestSellReference() : BigDecimal.ZERO;
        BigDecimal normalizedFallback = fallbackReference != null && fallbackReference.compareTo(BigDecimal.ZERO) > 0 ? fallbackReference : BigDecimal.ZERO;
        long referenceAt = status.getLowestSellReferenceAt();
        BigDecimal reference7d = status.getLowestSellReference7d() != null ? status.getLowestSellReference7d() : referenceLowest;
        long referenceAt7d = status.getLowestSellReferenceAt7d();
        BigDecimal reference30d = status.getLowestSellReference30d() != null ? status.getLowestSellReference30d() : referenceLowest;
        long referenceAt30d = status.getLowestSellReferenceAt30d();
        long now = System.currentTimeMillis();
        if (normalizedCurrent.compareTo(BigDecimal.ZERO) > 0) {
            if (referenceLowest.compareTo(BigDecimal.ZERO) <= 0) {
                referenceLowest = normalizedFallback.compareTo(BigDecimal.ZERO) > 0 ? normalizedFallback : normalizedCurrent;
                referenceAt = now;
            } else if (referenceAt <= 0L || now - referenceAt >= TimeUnit.DAYS.toMillis(3L)) {
                referenceLowest = normalizedCurrent;
                referenceAt = now;
            }
            if (reference7d.compareTo(BigDecimal.ZERO) <= 0) {
                reference7d = normalizedFallback.compareTo(BigDecimal.ZERO) > 0 ? normalizedFallback : normalizedCurrent;
                referenceAt7d = now;
            } else if (referenceAt7d <= 0L || now - referenceAt7d >= TimeUnit.DAYS.toMillis(7L)) {
                reference7d = normalizedCurrent;
                referenceAt7d = now;
            }
            if (reference30d.compareTo(BigDecimal.ZERO) <= 0) {
                reference30d = normalizedFallback.compareTo(BigDecimal.ZERO) > 0 ? normalizedFallback : normalizedCurrent;
                referenceAt30d = now;
            } else if (referenceAt30d <= 0L || now - referenceAt30d >= TimeUnit.DAYS.toMillis(30L)) {
                reference30d = normalizedCurrent;
                referenceAt30d = now;
            }
        }
        status.setLowestSellCurrent(normalizedCurrent);
        status.setLowestSellReference(referenceLowest);
        status.setLowestSellReferenceAt(referenceAt);
        status.setLowestSellReference7d(reference7d);
        status.setLowestSellReferenceAt7d(referenceAt7d);
        status.setLowestSellReference30d(reference30d);
        status.setLowestSellReferenceAt30d(referenceAt30d);
        this.plugin.getItemManager().updateItemStatus(status);
    }

    private void broadcastNewListing(ExchangeItem exchangeItem) {
        if (exchangeItem == null) {
            return;
        }
        String name = exchangeItem.getDisplayName() != null && !exchangeItem.getDisplayName().isBlank()
            ? exchangeItem.getDisplayName() : "#" + exchangeItem.getId();
        this.plugin.getServer().broadcastMessage(
            "\u00a76\u65b0\u7684\u5546\u54c1\uff1a\u00a7f" + name + "\u00a76\u6b63\u5728\u5e02\u573a\u70ed\u5356\u4e2d\uff01");
    }

    private void broadcastNewBuyRequest(ExchangeItem exchangeItem, BigDecimal price) {
        if (exchangeItem == null || price == null) {
            return;
        }
        String name = exchangeItem.getDisplayName() != null && !exchangeItem.getDisplayName().isBlank()
            ? exchangeItem.getDisplayName() : "#" + exchangeItem.getId();
        this.plugin.getServer().broadcastMessage(
            "\u00a76" + name + " \u6b63\u5728\u5e02\u573a\u4ee5\u00a7f" + price.toPlainString()
                + "\u00a76 \u6c42\u8d2d\u4e2d\uff01");
    }

    private boolean returnEscrow(Order order) {
        if (order.getOrderType() == Order.OrderType.BUY) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            UUID playerUuid = this.parseUuid(order.getPlayerUuid());
            if (playerUuid == null) {
                return false;
            }
            if (escrow != null && (escrow.getAmount() == null
                || escrow.getAmount().compareTo(BigDecimal.ZERO) <= 0)) {
                return this.plugin.getStorageManager().deleteEscrow(
                    order.getId(), EscrowEntry.AssetType.MONEY);
            }
            if (escrow != null && escrow.getAmount() != null
                && escrow.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal amount = escrow.getAmount();
                if (!this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.MONEY)) {
                    return false;
                }
                if (!this.deliverRefundedMoney(playerUuid, amount)) {
                    this.plugin.getStorageManager().insertEscrow(escrow);
                    return false;
                }
            }
            return true;
        } else {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            if (escrow == null) {
                return order.getRemainingQty() <= 0;
            }
            int escrowQty = escrow.getQuantity();
            if (escrowQty <= 0) {
                return this.plugin.getStorageManager().deleteEscrow(
                    order.getId(), EscrowEntry.AssetType.ITEM);
            }
            int remainingFromOrder = order.getRemainingQty();
            int itemsToReturn = Math.min(escrowQty, remainingFromOrder);
            if (itemsToReturn <= 0) {
                return escrowQty <= 0;
            }
            if (!this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.ITEM)) {
                return false;
            }
            if (itemsToReturn > 0) {
                UUID playerUuid = this.parseUuid(order.getPlayerUuid());
                if (playerUuid == null
                    || !this.deliverMatchedItems(playerUuid, escrow.getItemBase64(), itemsToReturn)) {
                    this.plugin.getStorageManager().insertEscrow(escrow);
                    return false;
                }
            }
            return true;
        }
    }

    private boolean isValidSellEscrow(Order order, EscrowEntry escrow) {
        return order != null
            && order.getOrderType() == Order.OrderType.SELL
            && escrow != null
            && escrow.getAssetType() == EscrowEntry.AssetType.ITEM
            && order.getPlayerUuid() != null
            && order.getPlayerUuid().equals(escrow.getPlayerUuid())
            && escrow.getItemBase64() != null
            && !escrow.getItemBase64().isEmpty()
            && escrow.getQuantity() == order.getRemainingQty()
            && escrow.getQuantity() > 0;
    }

    private boolean isValidMoneyEscrow(Order order, EscrowEntry escrow, BigDecimal requiredAmount) {
        return order != null
            && order.getOrderType() == Order.OrderType.BUY
            && escrow != null
            && escrow.getAssetType() == EscrowEntry.AssetType.MONEY
            && order.getPlayerUuid() != null
            && order.getPlayerUuid().equals(escrow.getPlayerUuid())
            && escrow.getAmount() != null
            && requiredAmount != null
            && requiredAmount.compareTo(BigDecimal.ZERO) >= 0
            && escrow.getAmount().compareTo(requiredAmount) >= 0;
    }

    private boolean writeEscrowState(EscrowEntry previous, EscrowEntry next) {
        if (next.getAssetType() == EscrowEntry.AssetType.MONEY
            && next.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return this.plugin.getStorageManager().deleteEscrow(next.getOrderId(), next.getAssetType());
        }
        if (next.getAssetType() == EscrowEntry.AssetType.ITEM && next.getQuantity() <= 0) {
            return this.plugin.getStorageManager().deleteEscrow(next.getOrderId(), next.getAssetType());
        }
        return this.plugin.getStorageManager().insertEscrow(next);
    }

    private void restoreEscrowState(EscrowEntry escrow) {
        if (escrow == null || !this.plugin.getStorageManager().insertEscrow(this.copyEscrow(escrow))) {
            this.plugin.getLogger().severe("[AssetAudit] ESCROW_ROLLBACK_FAILED order="
                + (escrow == null ? "unknown" : escrow.getOrderId()));
        }
    }

    private void abortBuyOrderCreation(Order order, BigDecimal refundAmount) {
        Order cancelled = this.copyOrder(order);
        cancelled.setStatus(Order.OrderStatus.CANCELLED);
        if (!this.plugin.getStorageManager().updateOrder(cancelled)) {
            this.plugin.getLogger().severe("[AssetAudit] BUY_CREATE_CANCEL_FAILED order=" + order.getId());
        }
        EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(
            order.getId(), EscrowEntry.AssetType.MONEY);
        if (escrow != null && !this.plugin.getStorageManager().deleteEscrow(
            order.getId(), EscrowEntry.AssetType.MONEY)) {
            this.plugin.getLogger().severe("[AssetAudit] BUY_CREATE_ESCROW_DELETE_FAILED order=" + order.getId()
                + " amount=" + escrow.getAmount());
            return;
        }
        UUID playerUuid = this.parseUuid(order.getPlayerUuid());
        if (playerUuid == null || !this.deliverRefundedMoney(playerUuid, refundAmount)) {
            this.plugin.getLogger().severe("[AssetAudit] BUY_CREATE_REFUND_FAILED order=" + order.getId()
                + " amount=" + refundAmount);
        }
    }

    private Order copyOrder(Order source) {
        Order copy = new Order();
        copy.setId(source.getId());
        copy.setOrderType(source.getOrderType());
        copy.setItemId(source.getItemId());
        copy.setPlayerUuid(source.getPlayerUuid());
        copy.setPlayerName(source.getPlayerName());
        copy.setPrice(source.getPrice());
        copy.setQuantity(source.getQuantity());
        copy.setFilledQty(source.getFilledQty());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt() == null ? null : new Timestamp(source.getCreatedAt().getTime()));
        copy.setUpdatedAt(source.getUpdatedAt() == null ? null : new Timestamp(source.getUpdatedAt().getTime()));
        return copy;
    }

    private EscrowEntry copyEscrow(EscrowEntry source) {
        EscrowEntry copy = new EscrowEntry();
        copy.setOrderId(source.getOrderId());
        copy.setPlayerUuid(source.getPlayerUuid());
        copy.setAssetType(source.getAssetType());
        copy.setAmount(source.getAmount() == null ? BigDecimal.ZERO : source.getAmount());
        copy.setItemBase64(source.getItemBase64());
        copy.setQuantity(source.getQuantity());
        return copy;
    }

    private boolean deliverRefundedMoney(UUID playerUuid, BigDecimal amount) {
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline() && EconomyUtil.deposit(playerUuid, amount)) {
            this.plugin.getTradeNoticeBuffer().manual(playerUuid,
                "\u00a7a\u5df2\u9000\u8fd8 " + amount.toPlainString() + " " + this.plugin.getCurrencyName() + "\u3002");
            return true;
        }
        return this.plugin.getStorageManager().addToMoneyWarehouse(playerUuid.toString(), amount);
    }

    private boolean isValidPriceTick(BigDecimal price) {
        double tick = this.plugin.getPriceTick();
        if (price == null || tick <= 0.0) {
            return true;
        }
        BigDecimal remainder = price.remainder(BigDecimal.valueOf(tick));
        return remainder.compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean isPriceInConfiguredRange(BigDecimal price) {
        return price != null
            && price.compareTo(BigDecimal.valueOf(this.plugin.getMinPrice())) >= 0
            && price.compareTo(BigDecimal.valueOf(this.plugin.getMaxPrice())) <= 0;
    }

    public List<Order> getPlayerOrders(String playerUuid) {
        List<Order> orders = this.plugin.getStorageManager().getOrdersByPlayer(playerUuid);
        ArrayList<Order> activeOrders = new ArrayList<Order>();
        for (Order order : orders) {
            if (order == null || !order.isActive()) continue;
            activeOrders.add(order);
        }
        return activeOrders;
    }

    public Order getOrder(int id) {
        return this.plugin.getStorageManager().getOrder(id);
    }

    public List<Order> getActiveOrders(int itemId, Order.OrderType type) {
        return this.plugin.getStorageManager().getActiveOrdersByItem(itemId, type);
    }

    public int getCurrentSellStock(int itemId) {
        int total = 0;
        for (Order order : this.getActiveOrders(itemId, Order.OrderType.SELL)) {
            total += Math.max(0, order.getRemainingQty());
        }
        return total;
    }

    public BigDecimal getLowestSellPrice(int itemId) {
        List<Order> sellOrders = this.getActiveOrders(itemId, Order.OrderType.SELL);
        if (sellOrders.isEmpty()) {
            return null;
        }
        return sellOrders.get(0).getPrice();
    }

    public BigDecimal getHighestBuyPrice(int itemId) {
        List<Order> buyOrders = this.getActiveOrders(itemId, Order.OrderType.BUY);
        if (buyOrders.isEmpty()) {
            return null;
        }
        return buyOrders.get(0).getPrice();
    }

    public List<PriceLevel> getAggregatedPriceLevels(int itemId, Order.OrderType type) {
        List<Order> orders = this.getActiveOrders(itemId, type);
        if (orders.isEmpty()) {
            return new ArrayList<PriceLevel>();
        }
        ArrayList<PriceLevel> levels = new ArrayList<PriceLevel>();
        BigDecimal currentPrice = null;
        int sumQty = 0;
        for (Order order : orders) {
            if (currentPrice == null || order.getPrice().compareTo(currentPrice) != 0) {
                if (currentPrice != null) {
                    levels.add(new PriceLevel(currentPrice, sumQty));
                }
                currentPrice = order.getPrice();
                sumQty = order.getRemainingQty();
                continue;
            }
            sumQty += order.getRemainingQty();
        }
        if (currentPrice != null) {
            levels.add(new PriceLevel(currentPrice, sumQty));
        }
        return levels;
    }

    public static class PriceLevel {
        private final BigDecimal price;
        private final int totalQuantity;

        public PriceLevel(BigDecimal price, int totalQuantity) {
            this.price = price;
            this.totalQuantity = totalQuantity;
        }

        public BigDecimal getPrice() {
            return this.price;
        }

        public int getTotalQuantity() {
            return this.totalQuantity;
        }
    }

    private static final class MoneyDeliveryReceipt {
        private final UUID playerUuid;
        private final BigDecimal amount;
        private final boolean directDeposit;

        private MoneyDeliveryReceipt(UUID playerUuid, BigDecimal amount, boolean directDeposit) {
            this.playerUuid = playerUuid;
            this.amount = amount;
            this.directDeposit = directDeposit;
        }
    }

    private static final class InventoryRemoval {
        private final Player player;
        private final ItemStack[] originals;
        private final int[] removals;
        private final int removedQuantity;

        private InventoryRemoval(Player player, ItemStack[] originals, int[] removals, int removedQuantity) {
            this.player = player;
            this.originals = originals;
            this.removals = removals;
            this.removedQuantity = removedQuantity;
        }

        private int removedQuantity() {
            return this.removedQuantity;
        }

        private boolean matchesAppliedState() {
            PlayerInventory inventory = this.player.getInventory();
            for (int slot = 0; slot < this.removals.length; ++slot) {
                int removeAmount = this.removals[slot];
                if (removeAmount <= 0) {
                    continue;
                }
                ItemStack original = this.originals[slot];
                ItemStack current = inventory.getItem(slot);
                int expectedAmount = original.getAmount() - removeAmount;
                if (expectedAmount <= 0) {
                    if (current != null && current.getType() != Material.AIR) {
                        return false;
                    }
                    continue;
                }
                if (current == null || !current.isSimilar(original) || current.getAmount() != expectedAmount) {
                    return false;
                }
            }
            return true;
        }

        private void rollback() {
            PlayerInventory inventory = this.player.getInventory();
            for (int slot = 0; slot < this.removals.length; ++slot) {
                if (this.removals[slot] <= 0) {
                    continue;
                }
                ItemStack original = this.originals[slot];
                inventory.setItem(slot, original == null ? null : original.clone());
            }
            this.player.updateInventory();
        }
    }

    private static final class SellOrderCreation {
        private final Order order;
        private final String error;
        private final InventoryRemoval removal;

        private SellOrderCreation(Order order, String error, InventoryRemoval removal) {
            this.order = order;
            this.error = error;
            this.removal = removal;
        }
    }

    private static final class CategoryItem {
        private final ItemStack item;
        private int count;

        private CategoryItem(ItemStack item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    private static final class WebSellCreation {
        private final Order order;
        private final String error;

        private WebSellCreation(Order order, String error) {
            this.order = order;
            this.error = error;
        }

        private Order order() {
            return this.order;
        }

        private String error() {
            return this.error;
        }
    }
}
