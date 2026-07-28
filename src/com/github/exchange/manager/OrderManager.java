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
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.MarketGuiItem;
import com.github.exchange.util.TaxCalculator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
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
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isValidPriceTick(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002";
        }
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        int beforeCount = this.countSimilarItems(player, itemStack);
        if (beforeCount < quantity) {
            return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u8db3\u591f\u7684\u7269\u54c1\u3002";
        }
        InventoryRemoval removal = this.removeSimilarItems(player, itemStack, quantity);
        if (removal == null) {
            return "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        int afterCount = this.countSimilarItems(player, itemStack);
        if (afterCount != beforeCount - quantity) {
            removal.rollback();
            return "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
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
            removal.rollback();
            return "\u00a7c\u521b\u5efa\u8ba2\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        order.setId(orderId);
        exchangeItem.setLastStockedAt(new Timestamp(System.currentTimeMillis()));
        exchangeItem.setLastEmptyAt(null);
        this.plugin.getStorageManager().updateExchangeItem(exchangeItem);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(orderId);
        escrow.setPlayerUuid(player.getUniqueId().toString());
        escrow.setAssetType(EscrowEntry.AssetType.ITEM);
        escrow.setAmount(BigDecimal.ZERO);
        escrow.setItemBase64(exchangeItem.getItemBase64());
        escrow.setQuantity(quantity);
        this.plugin.getStorageManager().insertEscrow(escrow);
        EscrowEntry storedEscrow = this.plugin.getStorageManager().getEscrow(orderId, EscrowEntry.AssetType.ITEM);
        if (!this.isValidSellEscrow(order, storedEscrow)) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(order);
            this.plugin.getStorageManager().deleteEscrow(orderId, EscrowEntry.AssetType.ITEM);
            removal.rollback();
            this.plugin.getLogger().severe("[AssetAudit] SELL_CREATE_ABORT player=" + player.getUniqueId()
                + " order=" + orderId + " item=" + exchangeItem.getId() + " quantity=" + quantity
                + " reason=escrow_verification_failed");
            return "\u00a7c\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u7269\u54c1\u5df2\u539f\u69fd\u4f4d\u6062\u590d\u3002";
        }
        this.plugin.getLogger().info("[AssetAudit] SELL_CREATE player=" + player.getUniqueId()
            + " order=" + orderId + " item=" + exchangeItem.getId() + " removed=" + removal.removedQuantity()
            + " escrow=" + storedEscrow.getQuantity());
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5356\u5355 #" + orderId + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: " + price + ", \u6570\u91cf: " + quantity;
    }

    public synchronized String placeBuyOrder(Player player, ExchangeItem exchangeItem, BigDecimal price, int quantity) {
        if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002";
        }
        if (!this.isValidPriceTick(price)) {
            return "\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u6302\u5355\u3002";
        }
        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal escrowTotal = totalCost;
        UUID playerUuid = player.getUniqueId();
        if (!EconomyUtil.hasBalance(playerUuid, escrowTotal)) {
            return "\u00a7c\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + escrowTotal + ", \u5f53\u524d: " + EconomyUtil.getBalance(playerUuid);
        }
        if (!EconomyUtil.withdraw(playerUuid, escrowTotal)) {
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
            EconomyUtil.deposit(playerUuid, escrowTotal);
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
        this.plugin.getStorageManager().insertEscrow(escrow);
        this.matchOrder(order);
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u4e70\u5355 #" + orderId + " \u5df2\u521b\u5efa\uff01\u5355\u4ef7: " + price + ", \u6570\u91cf: " + quantity;
    }

    public synchronized String marketBuy(Player player, ExchangeItem exchangeItem, int quantity) {
        if (quantity <= 0) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5927\u4e8e0\u3002";
        }
        ItemStatus status = this.plugin.getItemManager().getItemStatus(exchangeItem.getId());
        if (status != null && status.isSuspended()) {
            return "\u00a7c\u8be5\u54c1\u79cd\u5df2\u505c\u724c\uff0c\u65e0\u6cd5\u5e02\u4ef7\u4ea4\u6613\u3002";
        }
        List<Order> sellOrders = this.getActiveOrders(exchangeItem.getId(), Order.OrderType.SELL);
        if (sellOrders.isEmpty()) {
            return "\u00a7c\u5f53\u524d\u6ca1\u6709\u53ef\u7528\u5356\u5355\uff0c\u65e0\u6cd5\u4e70\u5165\u3002";
        }
        int available = 0;
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
        return "\u00a7a\u5df2\u6309\u5f53\u524d\u5e02\u573a\u4ef7\u683c\u63d0\u4ea4\u4e70\u5165\u3002 " + result;
    }

    public synchronized String directBuyFromSellOrder(Player player, int sellOrderId, int quantity) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u8d2d\u4e70\u7269\u54c1\u3002";
        }
        if (quantity <= 0) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5927\u4e8e0\u3002";
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
        BigDecimal totalCost = sellOrder.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal escrowTotal = totalCost;
        UUID playerUuid = player.getUniqueId();
        if (!EconomyUtil.hasBalance(playerUuid, escrowTotal)) {
            return "\u00a7c\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981: " + escrowTotal + ", \u5f53\u524d: " + EconomyUtil.getBalance(playerUuid);
        }
        if (!EconomyUtil.withdraw(playerUuid, escrowTotal)) {
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
            EconomyUtil.deposit(playerUuid, escrowTotal);
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
        this.plugin.getStorageManager().insertEscrow(escrow);
        this.executeMatch(buyOrder, sellOrder, quantity);
        return "\u00a7a\u5df2\u8d2d\u4e70 " + exchangeItem.getDisplayName() + " x" + quantity + "\uff0c\u6210\u4ea4\u4ef7: " + sellOrder.getPrice();
    }

    public synchronized String directSellToBuyOrder(Player player, int buyOrderId, int quantity) {
        if (player == null) {
            return "\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u51fa\u552e\u7269\u54c1\u3002";
        }
        if (quantity <= 0) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5927\u4e8e0\u3002";
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
        ItemStack itemStack = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (itemStack == null) {
            return "\u00a7c\u7269\u54c1\u53cd\u5e8f\u5217\u5316\u5931\u8d25\u3002";
        }
        int beforeCount = this.countSimilarItems(player, itemStack);
        if (beforeCount < quantity) {
            return "\u00a7c\u80cc\u5305\u4e2d\u6ca1\u6709\u8db3\u591f\u7684\u7269\u54c1\u3002";
        }
        InventoryRemoval removal = this.removeSimilarItems(player, itemStack, quantity);
        if (removal == null) {
            return "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        int afterCount = this.countSimilarItems(player, itemStack);
        if (afterCount != beforeCount - quantity) {
            removal.rollback();
            return "\u00a7c\u7269\u54c1\u79fb\u9664\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        Order sellOrder = new Order();
        sellOrder.setOrderType(Order.OrderType.SELL);
        sellOrder.setItemId(exchangeItem.getId());
        sellOrder.setPlayerUuid(player.getUniqueId().toString());
        sellOrder.setPlayerName(player.getName());
        sellOrder.setPrice(buyOrder.getPrice());
        sellOrder.setQuantity(quantity);
        sellOrder.setFilledQty(0);
        sellOrder.setStatus(Order.OrderStatus.OPEN);
        sellOrder.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        sellOrder.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        int sellOrderId = this.plugin.getStorageManager().insertOrder(sellOrder);
        if (sellOrderId <= 0) {
            removal.rollback();
            return "\u00a7c\u521b\u5efa\u5356\u5355\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5\u3002";
        }
        sellOrder.setId(sellOrderId);
        EscrowEntry escrow = new EscrowEntry();
        escrow.setOrderId(sellOrderId);
        escrow.setPlayerUuid(player.getUniqueId().toString());
        escrow.setAssetType(EscrowEntry.AssetType.ITEM);
        escrow.setAmount(BigDecimal.ZERO);
        escrow.setItemBase64(exchangeItem.getItemBase64());
        escrow.setQuantity(quantity);
        this.plugin.getStorageManager().insertEscrow(escrow);
        EscrowEntry storedEscrow = this.plugin.getStorageManager().getEscrow(sellOrderId, EscrowEntry.AssetType.ITEM);
        if (!this.isValidSellEscrow(sellOrder, storedEscrow)) {
            sellOrder.setStatus(Order.OrderStatus.CANCELLED);
            this.plugin.getStorageManager().updateOrder(sellOrder);
            this.plugin.getStorageManager().deleteEscrow(sellOrderId, EscrowEntry.AssetType.ITEM);
            removal.rollback();
            this.plugin.getLogger().severe("[AssetAudit] SELL_TO_BUY_ABORT player=" + player.getUniqueId()
                + " sellOrder=" + sellOrderId + " buyOrder=" + buyOrderId + " item=" + exchangeItem.getId()
                + " quantity=" + quantity + " reason=escrow_verification_failed");
            return "\u00a7c\u6258\u7ba1\u5199\u5165\u5931\u8d25\uff0c\u7269\u54c1\u5df2\u539f\u69fd\u4f4d\u6062\u590d\u3002";
        }
        this.plugin.getLogger().info("[AssetAudit] SELL_TO_BUY player=" + player.getUniqueId()
            + " sellOrder=" + sellOrderId + " buyOrder=" + buyOrderId + " item=" + exchangeItem.getId()
            + " removed=" + removal.removedQuantity() + " escrow=" + storedEscrow.getQuantity());
        this.executeMatch(buyOrder, sellOrder, quantity);
        this.refreshLowestSellStatus(exchangeItem.getId());
        return "\u00a7a\u5df2\u51fa\u552e " + exchangeItem.getDisplayName() + " x" + quantity + "\uff0c\u6210\u4ea4\u4ef7: " + buyOrder.getPrice();
    }

    public synchronized String marketSell(Player player, ExchangeItem exchangeItem, int quantity) {
        BigDecimal marketPrice;
        if (quantity <= 0) {
            return "\u00a7c\u6570\u91cf\u5fc5\u987b\u5927\u4e8e0\u3002";
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
        return "\u00a7a\u5df2\u6309\u5e02\u4ef7(" + marketPrice + ")\u63d0\u4ea4\u5356\u5355\u3002" + result;
    }

    public synchronized String quickSellAll(Player player, ExchangeItem exchangeItem) {
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
        if (!strictHash) {
            return true;
        }
        String stackHash = ItemSerializer.calculateNbtHash(stack);
        if (targetHash.equals(stackHash)) {
            return true;
        }
        ItemStack normalizedTarget = target.clone();
        ItemStack normalizedStack = stack.clone();
        normalizedTarget.setAmount(1);
        normalizedStack.setAmount(1);
        String targetBase64 = ItemSerializer.itemToBase64(normalizedTarget);
        String stackBase64 = ItemSerializer.itemToBase64(normalizedStack);
        return targetBase64 != null && targetBase64.equals(stackBase64);
    }

    public synchronized String cancelOrder(Player player, int orderId) {
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
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        this.plugin.getStorageManager().updateOrder(order);
        this.returnEscrow(order);
        if (order.getOrderType() == Order.OrderType.SELL) {
            this.plugin.getLogger().info("[AssetAudit] SELL_CANCEL player=" + player.getUniqueId()
                + " order=" + orderId + " item=" + order.getItemId() + " refund=" + order.getRemainingQty());
            this.refreshLowestSellStatus(order.getItemId());
        }
        return "\u00a7a\u8ba2\u5355 #" + orderId + " \u5df2\u53d6\u6d88\uff0c\u8d44\u4ea7\u5df2\u9000\u56de\u3002";
    }

    private void matchOrder(Order newOrder) {
        if (newOrder.getOrderType() == Order.OrderType.BUY) {
            List<Order> sellOrders = this.plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.SELL);
            for (Order sellOrder : sellOrders) {
                if (newOrder.isActive() && sellOrder.getPrice().compareTo(newOrder.getPrice()) <= 0) {
                    int matchQty = Math.min(newOrder.getRemainingQty(), sellOrder.getRemainingQty());
                    if (matchQty <= 0) continue;
                    this.executeMatch(newOrder, sellOrder, matchQty);
                    continue;
                }
                break;
            }
        } else {
            List<Order> buyOrders = this.plugin.getStorageManager().getActiveOrdersByItem(newOrder.getItemId(), Order.OrderType.BUY);
            for (Order buyOrder : buyOrders) {
                if (newOrder.isActive() && buyOrder.getPrice().compareTo(newOrder.getPrice()) >= 0) {
                    int matchQty = Math.min(newOrder.getRemainingQty(), buyOrder.getRemainingQty());
                    if (matchQty <= 0) continue;
                    this.executeMatch(buyOrder, newOrder, matchQty);
                    continue;
                }
                break;
            }
        }
    }

    private void executeMatch(Order buyOrder, Order sellOrder, int quantity) {
        EscrowEntry sellerEscrow;
        BigDecimal lowestSellBeforeTrade = this.getLowestSellPrice(buyOrder.getItemId());
        BigDecimal tradePrice = buyOrder.getCreatedAt().before(sellOrder.getCreatedAt()) ? buyOrder.getPrice() : sellOrder.getPrice();
        BigDecimal totalAmount = tradePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal buyerFee = BigDecimal.ZERO.setScale(2);
        BigDecimal sellerFee = this.calculateSellerFee(totalAmount);
        UUID buyerUuid = UUID.fromString(buyOrder.getPlayerUuid());
        UUID sellerUuid = UUID.fromString(sellOrder.getPlayerUuid());
        boolean activeSellOrder = sellOrder.getCreatedAt().after(buyOrder.getCreatedAt());
        EscrowEntry buyerEscrow = this.plugin.getStorageManager().getEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
        if (buyerEscrow != null) {
            BigDecimal matchedMoney = tradePrice.multiply(BigDecimal.valueOf(quantity));
            buyerEscrow.setAmount(buyerEscrow.getAmount().subtract(matchedMoney));
            if (buyerEscrow.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                this.plugin.getStorageManager().deleteEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            } else {
                this.plugin.getStorageManager().insertEscrow(buyerEscrow);
            }
        }
        BigDecimal sellerReceives = totalAmount.subtract(sellerFee);
        this.deliverMatchedMoney(sellerUuid, sellerReceives);
        if ((sellerEscrow = this.plugin.getStorageManager().getEscrow(sellOrder.getId(), EscrowEntry.AssetType.ITEM)) != null) {
            sellerEscrow.setQuantity(sellerEscrow.getQuantity() - quantity);
            if (sellerEscrow.getQuantity() <= 0) {
                this.plugin.getStorageManager().deleteEscrow(sellOrder.getId(), EscrowEntry.AssetType.ITEM);
            } else {
                this.plugin.getStorageManager().insertEscrow(sellerEscrow);
            }
        }
        ExchangeItem matchedItem = this.plugin.getItemManager().getItem(buyOrder.getItemId());
        this.deliverMatchedItems(buyerUuid, matchedItem, quantity);
        buyOrder.setFilledQty(buyOrder.getFilledQty() + quantity);
        sellOrder.setFilledQty(sellOrder.getFilledQty() + quantity);
        if (buyOrder.getFilledQty() >= buyOrder.getQuantity()) {
            buyOrder.setStatus(Order.OrderStatus.CLOSED);
            EscrowEntry remainingEscrow = this.plugin.getStorageManager().getEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            if (remainingEscrow != null && remainingEscrow.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                this.plugin.getStorageManager().addToMoneyWarehouse(buyOrder.getPlayerUuid(), remainingEscrow.getAmount());
                this.plugin.getStorageManager().deleteEscrow(buyOrder.getId(), EscrowEntry.AssetType.MONEY);
            }
        } else {
            buyOrder.setStatus(Order.OrderStatus.PARTIAL);
        }
        if (sellOrder.getFilledQty() >= sellOrder.getQuantity()) {
            sellOrder.setStatus(Order.OrderStatus.CLOSED);
        } else {
            sellOrder.setStatus(Order.OrderStatus.PARTIAL);
        }
        this.plugin.getStorageManager().updateOrder(buyOrder);
        this.plugin.getStorageManager().updateOrder(sellOrder);
        this.recordTrade(buyOrder.getItemId(), buyOrder.getPlayerUuid(), sellOrder.getPlayerUuid(), tradePrice, quantity, totalAmount, buyerFee, sellerFee, buyOrder.getId(), sellOrder.getId());
        this.plugin.collectTax(sellerFee);
        this.updateItemStatusAfterTrade(buyOrder.getItemId(), tradePrice, quantity);
        this.refreshLowestSellStatus(buyOrder.getItemId(), lowestSellBeforeTrade);
    }

    private void giveItemsToPlayer(Player player, ExchangeItem exchangeItem, int quantity) {
        if (exchangeItem == null) {
            return;
        }
        this.plugin.getStorageManager().addToWarehouse(exchangeItem.getItemBase64(), quantity);
        if (player != null && player.isOnline()) {
            player.sendMessage("\u00a7a\u4f60\u83b7\u5f97\u4e86 " + exchangeItem.getDisplayName() + " x" + quantity + " (\u5df2\u5b58\u5165\u4ed3\u5e93\uff0c\u4f7f\u7528 /se withdraw \u63d0\u53d6)");
        }
    }

    private void deliverMatchedItems(UUID buyerUuid, ExchangeItem exchangeItem, int quantity) {
        if (exchangeItem == null || quantity <= 0) {
            return;
        }
        ItemStack baseItem = ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (baseItem == null) {
            this.plugin.getStorageManager().addToPlayerItemWarehouse(buyerUuid.toString(), exchangeItem.getItemBase64(), quantity);
            return;
        }
        Player buyer = Bukkit.getPlayer(buyerUuid);
        if (buyer == null || !buyer.isOnline()) {
            this.plugin.getStorageManager().addToPlayerItemWarehouse(buyerUuid.toString(), exchangeItem.getItemBase64(), quantity);
            return;
        }
        int maxStack = Math.max(1, baseItem.getMaxStackSize());
        for (int chunkAmount : DeliveryPlan.chunks(quantity, maxStack)) {
            ItemStack giveStack = baseItem.clone();
            giveStack.setAmount(chunkAmount);
            for (ItemStack leftover : buyer.getInventory().addItem(new ItemStack[]{giveStack}).values()) {
                if (leftover == null) {
                    continue;
                }
                buyer.getWorld().dropItemNaturally(buyer.getLocation(), leftover);
            }
        }
        buyer.sendMessage("\u00a7a\u4f60\u5df2\u83b7\u5f97 " + exchangeItem.getDisplayName() + " x" + quantity + "\u3002");
    }

    private void deliverMatchedMoney(UUID sellerUuid, BigDecimal amount) {
        if (sellerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller != null && seller.isOnline() && EconomyUtil.deposit(sellerUuid, amount)) {
            seller.sendMessage("\u00a7a\u4f60\u552e\u51fa\u7269\u54c1\u83b7\u5f97\u4e86 " + amount.toPlainString() + " " + this.plugin.getCurrencyName() + "\u3002");
            return;
        }
        this.plugin.getStorageManager().addToMoneyWarehouse(sellerUuid.toString(), amount);
    }

    private void recordTrade(int itemId, String buyerUuid, String sellerUuid, BigDecimal price, int quantity, BigDecimal totalAmount, BigDecimal buyerFee, BigDecimal sellerFee, int buyOrderId, int sellOrderId) {
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
        this.plugin.getStorageManager().insertTrade(trade);
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

    private void returnEscrow(Order order) {
        if (order.getOrderType() == Order.OrderType.BUY) {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            if (escrow != null && escrow.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                this.deliverRefundedMoney(UUID.fromString(order.getPlayerUuid()), escrow.getAmount());
                this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.MONEY);
            }
        } else {
            EscrowEntry escrow = this.plugin.getStorageManager().getEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            int escrowQty = escrow != null ? escrow.getQuantity() : 0;
            int remainingFromOrder = order.getRemainingQty();
            int itemsToReturn = Math.min(escrowQty, remainingFromOrder);
            if (escrow != null) {
                this.plugin.getStorageManager().deleteEscrow(order.getId(), EscrowEntry.AssetType.ITEM);
            }
            if (itemsToReturn > 0) {
                ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(order.getItemId());
                if (exchangeItem != null) {
                    this.deliverMatchedItems(UUID.fromString(order.getPlayerUuid()), exchangeItem, itemsToReturn);
                } else if (escrow != null) {
                    this.plugin.getStorageManager().addToPlayerItemWarehouse(order.getPlayerUuid(), escrow.getItemBase64(), itemsToReturn);
                }
            }
        }
    }

    private boolean isValidSellEscrow(Order order, EscrowEntry escrow) {
        return order != null
            && order.getOrderType() == Order.OrderType.SELL
            && escrow != null
            && escrow.getAssetType() == EscrowEntry.AssetType.ITEM
            && order.getPlayerUuid().equals(escrow.getPlayerUuid())
            && escrow.getItemBase64() != null
            && !escrow.getItemBase64().isEmpty()
            && escrow.getQuantity() == order.getRemainingQty()
            && escrow.getQuantity() > 0;
    }

    private void deliverRefundedMoney(UUID playerUuid, BigDecimal amount) {
        if (playerUuid == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline() && EconomyUtil.deposit(playerUuid, amount)) {
            player.sendMessage("\u00a7a\u5df2\u9000\u8fd8 " + amount.toPlainString() + " " + this.plugin.getCurrencyName() + "\u3002");
            return;
        }
        this.plugin.getStorageManager().addToMoneyWarehouse(playerUuid.toString(), amount);
    }

    private boolean isValidPriceTick(BigDecimal price) {
        double tick = this.plugin.getPriceTick();
        if (tick <= 0.0) {
            return true;
        }
        BigDecimal remainder = price.remainder(BigDecimal.valueOf(tick));
        return remainder.compareTo(BigDecimal.ZERO) == 0;
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
}
