package com.github.exchange.warehouse;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.github.exchange.StockExchangePlugin;
import com.github.exchange.manager.ItemManager;
import com.github.exchange.model.EscrowEntry;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.model.Order;
import com.github.exchange.util.DurableFiles;
import com.github.exchange.util.ItemSerializer;
import com.github.exchange.util.MarketGuiItem;
import com.github.exchange.util.SpecialCategory;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class AutoWarehouseManager implements Listener {
    private static final String NO_CHEST_MESSAGE = "§c请站在任意箱子上再使用该按钮";
    /*
     * Inventory events can arrive once for every hopper transfer.  Reconciling
     * the entire chest on every event serializes every stack and writes each
     * changed order synchronously. Sell warehouses instead reconcile on the
     * five-second periodic pass; buy warehouses still coalesce their delivery
     * events before syncing.
     */
    private static final long PERIODIC_RECONCILE_TICKS = 100L;
    private static final long EVENT_SYNC_DELAY_TICKS = 10L;

    private final StockExchangePlugin plugin;
    private final File storageFile;
    private final NamespacedKey warehouseIdKey;
    private final NamespacedKey warehouseTypeKey;
    private final Map<String, WarehouseRecord> records = new LinkedHashMap<String, WarehouseRecord>();
    private final Map<BlockKey, String> activeWarehouseByBlock = new HashMap<BlockKey, String>();
    private final Map<Integer, OrderLink> sellOrderLinks = new HashMap<Integer, OrderLink>();
    private final Map<String, PendingBuyDelivery> buyDeliveries =
        new LinkedHashMap<String, PendingBuyDelivery>();
    private final Map<UUID, PendingSellConfiguration> pendingSellConfigurations =
        new HashMap<UUID, PendingSellConfiguration>();
    private final Set<String> scheduledWarehouseSyncs = new HashSet<String>();
    private final Set<String> lockedWarehouseIds = new HashSet<String>();
    private final Set<String> structureVerificationLocks =
        new HashSet<String>();
    private PendingSaleTransaction pendingSaleTransaction;
    private PendingBuyTransfer pendingBuyTransfer;
    private BukkitTask periodicTask;
    private boolean dataLoaded;

    public AutoWarehouseManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "physical-warehouses.yml");
        this.warehouseIdKey = new NamespacedKey(plugin, "physical_warehouse_id");
        this.warehouseTypeKey = new NamespacedKey(plugin, "physical_warehouse_type");
    }

    public void start() {
        if (!this.plugin.isStorageAvailable()) {
            this.dataLoaded = false;
            return;
        }
        this.dataLoaded = false;
        this.activeWarehouseByBlock.clear();
        this.sellOrderLinks.clear();
        this.scheduledWarehouseSyncs.clear();
        this.lockedWarehouseIds.clear();
        this.structureVerificationLocks.clear();
        this.load();
        if (!this.plugin.isStorageAvailable()) {
            this.stopForStorageFailure();
            return;
        }
        this.dataLoaded = true;
        this.recoverPendingTransactions();
        if (!this.plugin.isStorageAvailable()) {
            this.stopForStorageFailure();
            return;
        }
        this.cleanupCommittedBuyDeliveries();
        if (!this.plugin.isStorageAvailable()) {
            this.stopForStorageFailure();
            return;
        }
        this.rebuildIndexesAndRecoverLinks();
        if (!this.plugin.isStorageAvailable()) {
            this.stopForStorageFailure();
            return;
        }
        this.periodicTask = Bukkit.getScheduler().runTaskTimer(
            this.plugin,
            this::reconcileLoadedWarehouses,
            PERIODIC_RECONCILE_TICKS,
            PERIODIC_RECONCILE_TICKS
        );
        Bukkit.getScheduler().runTask(this.plugin, this::reconcileLoadedWarehouses);
    }

    public void stopForStorageFailure() {
        if (this.periodicTask != null) {
            this.periodicTask.cancel();
            this.periodicTask = null;
        }
        this.lockedWarehouseIds.addAll(this.records.keySet());
        this.pendingSellConfigurations.clear();
        this.scheduledWarehouseSyncs.clear();
        this.structureVerificationLocks.clear();
        this.dataLoaded = false;
    }

    public void shutdown() {
        if (this.periodicTask != null) {
            this.periodicTask.cancel();
            this.periodicTask = null;
        }
        this.pendingSellConfigurations.clear();
        this.scheduledWarehouseSyncs.clear();
        this.lockedWarehouseIds.clear();
        this.structureVerificationLocks.clear();
        if (this.dataLoaded) {
            this.save();
        }
        this.dataLoaded = false;
        this.records.clear();
        this.activeWarehouseByBlock.clear();
        this.sellOrderLinks.clear();
        this.buyDeliveries.clear();
        this.pendingSaleTransaction = null;
        this.pendingBuyTransfer = null;
    }

    public void handleSellConfigurationButton(Player player) {
        if (player == null) {
            return;
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            player.sendMessage("§c交易仓库当前不可用，请联系管理员处理。");
            return;
        }
        ChestTarget target = this.resolveStandingChest(player);
        if (target == null) {
            player.sendMessage(NO_CHEST_MESSAGE);
            return;
        }
        ConfigurationTargetCheck check = this.inspectConfigurationTarget(target);
        if (!check.available()) {
            player.sendMessage(check.message());
            return;
        }
        if (check.existing() != null) {
            this.handleExistingConfiguration(player, check.existing(), WarehouseType.SELL);
            return;
        }
        if (!this.hasContainerAccess(player, target.blocks())) {
            player.sendMessage("§c你没有该领地的容器使用权限，无法配置仓库。");
            return;
        }
        this.pendingSellConfigurations.put(
            player.getUniqueId(),
            new PendingSellConfiguration(target.blocks(), System.currentTimeMillis())
        );
        this.plugin.getChatInputHandler().startAutoSellWarehousePriceInput(player);
    }

    public void handleBuyConfigurationButton(Player player) {
        if (player == null) {
            return;
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            player.sendMessage("§c交易仓库当前不可用，请联系管理员处理。");
            return;
        }
        ChestTarget target = this.resolveStandingChest(player);
        if (target == null) {
            player.sendMessage(NO_CHEST_MESSAGE);
            return;
        }
        ConfigurationTargetCheck check = this.inspectConfigurationTarget(target);
        if (!check.available()) {
            player.sendMessage(check.message());
            return;
        }
        if (check.existing() != null) {
            this.handleExistingConfiguration(player, check.existing(), WarehouseType.BUY);
            return;
        }
        if (!this.hasContainerAccess(player, target.blocks())) {
            player.sendMessage("§c你没有该领地的容器使用权限，无法配置仓库。");
            return;
        }
        WarehouseRecord record = this.createRecord(player, target, WarehouseType.BUY, null);
        if (record == null) {
            player.sendMessage("§c求购收货仓库配置保存失败，请稍后重试。");
            return;
        }
        this.applyWarehouseName(record);
        this.routePendingBuyDeliveries(player.getUniqueId());
        player.sendMessage("§a已将脚下箱子配置为求购收货仓库。");
    }

    public void completePendingSellConfiguration(Player player, BigDecimal price) {
        if (player == null || price == null) {
            return;
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            this.pendingSellConfigurations.remove(player.getUniqueId());
            player.sendMessage("§c交易仓库当前不可用，请联系管理员处理。");
            return;
        }
        PendingSellConfiguration pending = this.pendingSellConfigurations.remove(player.getUniqueId());
        if (pending == null) {
            player.sendMessage("§c仓库配置已失效，请重新站在箱子上操作。");
            return;
        }
        if (System.currentTimeMillis() - pending.createdAt() > 300000L) {
            player.sendMessage("§c仓库配置已超时，请重新站在箱子上操作。");
            return;
        }
        ChestTarget target = this.resolveStandingChest(player);
        if (target == null || !sameBlocks(target.blocks(), pending.blocks())) {
            player.sendMessage("§c箱体结构已经变化，请重新站在箱子上配置。");
            return;
        }
        ConfigurationTargetCheck check = this.inspectConfigurationTarget(target);
        if (!check.available()) {
            player.sendMessage(check.message());
            return;
        }
        if (check.existing() != null) {
            player.sendMessage("§c该箱子已经被配置为交易仓库。");
            return;
        }
        if (!this.hasContainerAccess(player, target.blocks())) {
            player.sendMessage("§c你没有该领地的容器使用权限，无法配置仓库。");
            return;
        }
        WarehouseRecord record = this.createRecord(player, target, WarehouseType.SELL, price);
        if (record == null) {
            player.sendMessage("§c自动出售仓库配置保存失败，请稍后重试。");
            return;
        }
        this.applyWarehouseName(record);
        SyncResult result = this.syncSellWarehouse(record, true);
        player.sendMessage("§a已将脚下箱子配置为自动出售仓库，单价："
            + formatPrice(price) + "。");
        if (result.failedKinds() > 0) {
            player.sendMessage("§e有 " + result.failedKinds()
                + " 种物品暂未成功上架；物品仍安全保留在箱内，系统会继续重试。");
        }
    }

    public void cancelPendingSellConfiguration(Player player) {
        if (player != null) {
            this.pendingSellConfigurations.remove(player.getUniqueId());
        }
    }

    public boolean isWarehouseSellOrder(int orderId) {
        Order order = this.plugin.getStorageManager().getOrder(orderId);
        EscrowEntry escrow = this.plugin.getStorageManager()
            .getEscrow(orderId, EscrowEntry.AssetType.ITEM);
        return order != null
            && escrow != null
            && order.getSourceWarehouseId() != null
            && !order.getSourceWarehouseId().isBlank()
            && order.getSourceWarehouseId().equals(escrow.getSourceWarehouseId());
    }

    public boolean isSettlementLockedOrder(int orderId) {
        PendingSaleTransaction transaction = this.pendingSaleTransaction;
        return orderId > 0
            && transaction != null
            && (transaction.orderId() == orderId
                || transaction.buyOrderId() == orderId);
    }

    public boolean bindSellOrder(
        String warehouseId,
        String itemBase64,
        int orderId
    ) {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return false;
        }
        WarehouseRecord record = this.records.get(warehouseId);
        if (record == null || !record.active() || record.type() != WarehouseType.SELL
            || itemBase64 == null || itemBase64.isBlank() || orderId <= 0) {
            return false;
        }
        Integer existingOrder = record.sellOrders().get(itemBase64);
        if (existingOrder != null && existingOrder.intValue() != orderId) {
            return false;
        }
        OrderLink requestedLink = new OrderLink(warehouseId, itemBase64);
        OrderLink existingLink = this.sellOrderLinks.get(orderId);
        if (existingLink != null && !existingLink.equals(requestedLink)) {
            return false;
        }
        Integer previousOrder = record.sellOrders().put(itemBase64, orderId);
        OrderLink previousLink = this.sellOrderLinks.put(
            orderId,
            requestedLink
        );
        if (this.save()) {
            return true;
        }
        if (previousOrder == null) {
            record.sellOrders().remove(itemBase64);
        } else {
            record.sellOrders().put(itemBase64, previousOrder);
        }
        if (previousLink == null) {
            this.sellOrderLinks.remove(orderId);
        } else {
            this.sellOrderLinks.put(orderId, previousLink);
        }
        return false;
    }

    public void unbindSellOrder(int orderId) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(
                this.plugin,
                () -> this.unbindSellOrder(orderId)
            );
            return;
        }
        OrderLink link = this.sellOrderLinks.remove(orderId);
        if (link == null) {
            return;
        }
        WarehouseRecord record = this.records.get(link.warehouseId());
        if (record != null) {
            record.sellOrders().remove(link.itemBase64(), orderId);
        }
        if (!this.save()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_LINK_SAVE_FAILED order=" + orderId
            );
        }
    }

    public SaleReservation reserveSale(
        Order buyOrder,
        Order sellOrder,
        EscrowEntry escrow,
        int quantity
    ) {
        if (sellOrder == null
            || sellOrder.getSourceWarehouseId() == null
            || sellOrder.getSourceWarehouseId().isBlank()) {
            return SaleReservation.notWarehouse();
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return SaleReservation.blocked();
        }
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order="
                    + sellOrder.getId() + " reason=off_main_thread"
            );
            return SaleReservation.blocked();
        }
        try {
            return this.reserveSaleOnMain(
                buyOrder,
                sellOrder,
                escrow,
                quantity
            );
        } catch (Throwable throwable) {
            PendingSaleTransaction transaction = this.pendingSaleTransaction;
            if (transaction != null
                && sellOrder != null
                && transaction.orderId() == sellOrder.getId()) {
                this.quarantineSaleReservation(
                    transaction.id(),
                    "sale reservation exception: "
                        + throwable.getClass().getSimpleName()
                );
            }
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order="
                    + (sellOrder == null ? "unknown" : sellOrder.getId())
                    + " reason=reservation_exception",
                throwable
            );
            return SaleReservation.blocked();
        }
    }

    private SaleReservation reserveSaleOnMain(
        Order buyOrder,
        Order sellOrder,
        EscrowEntry escrow,
        int quantity
    ) {
        String warehouseId = sellOrder == null
            ? null
            : sellOrder.getSourceWarehouseId();
        if (buyOrder == null || sellOrder == null || escrow == null || quantity <= 0
            || buyOrder.getOrderType() != Order.OrderType.BUY
            || warehouseId == null || warehouseId.isBlank()
            || !warehouseId.equals(escrow.getSourceWarehouseId())) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED reason=invalid_source"
            );
            return SaleReservation.blocked();
        }
        WarehouseRecord record = this.records.get(warehouseId);
        OrderLink link = this.sellOrderLinks.get(sellOrder.getId());
        Integer authoritativeOrderId = record == null
            ? null
            : record.sellOrders().get(escrow.getItemBase64());
        if (record == null || !record.active() || record.type() != WarehouseType.SELL
            || link == null || !record.id().equals(link.warehouseId())
            || !escrow.getItemBase64().equals(link.itemBase64())
            || authoritativeOrderId == null
            || authoritativeOrderId.intValue() != sellOrder.getId()
            || !sellOrder.getPlayerUuid().equals(record.ownerUuid().toString())) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " reason=source_not_active"
            );
            return SaleReservation.blocked();
        }
        ChestTarget target = this.resolveRecordTarget(record, true);
        if (target == null) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " warehouse=" + record.id() + " reason=chest_missing_or_changed"
            );
            this.scheduleSync(record.id());
            return SaleReservation.blocked();
        }
        if (this.isWarehouseLocked(record.id())
            || this.pendingSaleTransaction != null) {
            this.plugin.getLogger().warning(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " reason=warehouse_busy"
            );
            return SaleReservation.blocked();
        }
        int actualQuantity = countSimilar(target.inventory(), escrow.getItemBase64());
        if (actualQuantity != sellOrder.getRemainingQty()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " warehouse=" + record.id()
                    + " market=" + sellOrder.getRemainingQty()
                    + " chest=" + actualQuantity
                    + " reason=stock_mismatch"
            );
            this.scheduleSync(record.id());
            return SaleReservation.blocked();
        }
        ItemStack[] before = cloneContents(target.inventory());
        ItemStack[] after = cloneContents(target.inventory());
        int removed = removeSimilar(after, escrow.getItemBase64(), quantity);
        if (removed != quantity) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " requested=" + quantity + " removed=" + removed
                    + " reason=physical_removal_failed"
            );
            return SaleReservation.blocked();
        }
        List<String> beforeSnapshot = serializeContents(before);
        List<String> afterSnapshot = serializeContents(after);
        if (beforeSnapshot == null || afterSnapshot == null) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_MATCH_BLOCKED order=" + sellOrder.getId()
                    + " reason=snapshot_serialization_failed"
            );
            return SaleReservation.blocked();
        }
        PendingSaleTransaction transaction = new PendingSaleTransaction(
            UUID.randomUUID().toString(),
            record.id(),
            sellOrder.getId(),
            buyOrder.getId(),
            sellOrder.getFilledQty() + quantity,
            buyOrder.getFilledQty() + quantity,
            escrow.getItemBase64(),
            quantity,
            beforeSnapshot,
            afterSnapshot,
            WarehouseSaleRecoveryPolicy.Decision.PREPARED,
            ""
        );
        this.pendingSaleTransaction = transaction;
        this.lockedWarehouseIds.add(record.id());
        if (!this.save()) {
            this.pendingSaleTransaction = null;
            this.lockedWarehouseIds.remove(record.id());
            return SaleReservation.blocked();
        }
        try {
            target.inventory().setContents(after);
        } catch (Throwable throwable) {
            this.quarantineSaleReservation(
                transaction.id(),
                "sale reservation inventory write failed: "
                    + throwable.getClass().getSimpleName()
            );
            return SaleReservation.blocked();
        }
        List<String> verified = serializeContents(
            target.inventory().getContents()
        );
        if (!afterSnapshot.equals(verified)) {
            if (beforeSnapshot.equals(verified)) {
                this.pendingSaleTransaction = null;
                if (this.save()) {
                    this.lockedWarehouseIds.remove(record.id());
                } else {
                    this.pendingSaleTransaction = transaction;
                }
            } else {
                this.quarantineSaleReservation(
                    transaction.id(),
                    "sale reservation inventory verification failed"
                );
            }
            return SaleReservation.blocked();
        }
        return SaleReservation.reserved(this, transaction.id());
    }

    public void onWarehouseOrderSettled(
        Order settledOrder,
        EscrowEntry originalEscrow
    ) {
        if (settledOrder == null || originalEscrow == null
            || settledOrder.getSourceWarehouseId() == null
            || !settledOrder.getSourceWarehouseId().equals(
                originalEscrow.getSourceWarehouseId()
            )) {
            return;
        }
        if (this.isSettlementLockedOrder(settledOrder.getId())) {
            return;
        }
        if (!settledOrder.isActive() || settledOrder.getRemainingQty() <= 0) {
            this.unbindSellOrder(settledOrder.getId());
        }
    }

    public boolean hasActiveBuyWarehouse(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] BUY_WAREHOUSE_LOOKUP_BLOCKED player="
                    + playerUuid + " reason=off_main_thread"
            );
            return false;
        }
        return !this.getActiveWarehouses(playerUuid, WarehouseType.BUY).isEmpty();
    }

    public boolean enqueueBuyTradeDelivery(
        UUID playerUuid,
        String itemBase64,
        int quantity,
        String idempotencyKey
    ) {
        if (playerUuid == null || itemBase64 == null || itemBase64.isBlank()
            || quantity <= 0 || idempotencyKey == null
            || idempotencyKey.isBlank()) {
            return false;
        }
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] BUY_WAREHOUSE_DELIVERY_BLOCKED delivery="
                    + idempotencyKey + " reason=off_main_thread"
            );
            return false;
        }
        PendingBuyDelivery existing = this.buyDeliveries.get(idempotencyKey);
        if (existing != null) {
            boolean matching = existing.ownerUuid().equals(playerUuid)
                && existing.itemBase64().equals(itemBase64)
                && existing.originalQuantity() == quantity;
            return matching;
        }
        if (this.getActiveWarehouses(playerUuid, WarehouseType.BUY).isEmpty()) {
            return false;
        }
        PendingBuyDelivery delivery = new PendingBuyDelivery(
            idempotencyKey,
            playerUuid,
            itemBase64,
            quantity,
            quantity,
            System.currentTimeMillis()
        );
        this.buyDeliveries.put(idempotencyKey, delivery);
        if (!this.save()) {
            this.buyDeliveries.remove(idempotencyKey);
            return false;
        }
        return true;
    }

    public boolean confirmCommittedBuyDelivery(String idempotencyKey) {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()
            || idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        boolean settlementPending =
            this.plugin.getMatchSettlementJournal() != null
                && this.plugin.getMatchSettlementJournal().hasPending();
        PendingBuyDelivery delivery = this.buyDeliveries.get(idempotencyKey);
        if (delivery == null) {
            return !settlementPending;
        }
        if (settlementPending) {
            return false;
        }
        try {
            this.routePendingBuyDeliveries(delivery.ownerUuid());
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] BUY_WAREHOUSE_POST_COMMIT_ROUTE_FAILED delivery="
                    + idempotencyKey,
                throwable
            );
            return false;
        }
        delivery = this.buyDeliveries.get(idempotencyKey);
        if (delivery == null) {
            return true;
        }
        if (!BuyDeliveryRetentionPolicy.canDiscard(
                delivery.remainingQuantity(),
                false
            )) {
            return false;
        }
        this.buyDeliveries.remove(idempotencyKey);
        if (this.save()) {
            return true;
        }
        this.buyDeliveries.put(idempotencyKey, delivery);
        return false;
    }

    private void routePendingBuyDeliveriesSafely(
        UUID playerUuid,
        String idempotencyKey
    ) {
        try {
            this.routePendingBuyDeliveries(playerUuid);
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] BUY_WAREHOUSE_ROUTE_DEFERRED delivery="
                    + idempotencyKey
                    + " reason=" + throwable.getClass().getSimpleName(),
                throwable
            );
        }
    }

    private void routePendingBuyDeliveries(UUID playerUuid) {
        if (playerUuid == null || !Bukkit.isPrimaryThread()
            || !this.dataLoaded || !this.plugin.isStorageAvailable()
            || this.plugin.isSettlementDeliveryBlocked()) {
            return;
        }
        if (this.pendingBuyTransfer != null
            && !this.recoverPendingBuyTransfer()) {
            return;
        }
        List<WarehouseRecord> buyWarehouses = this.getActiveWarehouses(
            playerUuid,
            WarehouseType.BUY
        );
        if (buyWarehouses.isEmpty()) {
            return;
        }
        for (PendingBuyDelivery delivery :
            new ArrayList<PendingBuyDelivery>(this.buyDeliveries.values())) {
            if (!delivery.ownerUuid().equals(playerUuid)
                || delivery.remainingQuantity() <= 0) {
                continue;
            }
            ItemStack baseItem = ItemSerializer.itemFromBase64(
                delivery.itemBase64()
            );
            if (baseItem == null) {
                continue;
            }
            for (WarehouseRecord record : buyWarehouses) {
                if (delivery.remainingQuantity() <= 0) {
                    break;
                }
                if (this.isWarehouseLocked(record.id())) {
                    continue;
                }
                ChestTarget target = this.resolveRecordTarget(record, true);
                if (target == null) {
                    this.deactivateForInvalidStructure(record);
                    continue;
                }
                this.applyWarehouseName(record);
                ItemStack[] before = cloneContents(target.inventory());
                InventoryInsertionPlan plan = planInsertion(
                    before,
                    baseItem,
                    delivery.remainingQuantity()
                );
                if (plan.added() <= 0) {
                    continue;
                }
                List<String> beforeSnapshot = serializeContents(before);
                List<String> afterSnapshot =
                    serializeContents(plan.after());
                if (beforeSnapshot == null || afterSnapshot == null) {
                    this.plugin.getLogger().severe(
                        "[AssetAudit] BUY_WAREHOUSE_TRANSFER_BLOCKED delivery="
                            + delivery.idempotencyKey()
                            + " reason=snapshot_serialization_failed"
                    );
                    return;
                }
                PendingBuyTransfer transfer = new PendingBuyTransfer(
                    UUID.randomUUID().toString(),
                    delivery.idempotencyKey(),
                    record.id(),
                    plan.added(),
                    beforeSnapshot,
                    afterSnapshot
                );
                this.pendingBuyTransfer = transfer;
                this.lockedWarehouseIds.add(record.id());
                if (!this.save()) {
                    this.pendingBuyTransfer = null;
                    this.lockedWarehouseIds.remove(record.id());
                    return;
                }
                try {
                    target.inventory().setContents(plan.after());
                } catch (Throwable throwable) {
                    this.quarantineRecord(
                        record,
                        "buy transfer inventory write failed: "
                            + throwable.getClass().getSimpleName()
                    );
                    return;
                }
                List<String> verified = serializeContents(
                    target.inventory().getContents()
                );
                if (!afterSnapshot.equals(verified)) {
                    if (beforeSnapshot.equals(verified)) {
                        this.pendingBuyTransfer = null;
                        if (this.save()) {
                            this.lockedWarehouseIds.remove(record.id());
                        } else {
                            this.pendingBuyTransfer = transfer;
                        }
                    } else {
                        this.quarantineRecord(
                            record,
                            "buy transfer inventory verification failed"
                        );
                    }
                    return;
                }
                int previousRemaining = delivery.remainingQuantity();
                delivery.setRemainingQuantity(previousRemaining - plan.added());
                this.pendingBuyTransfer = null;
                if (!this.save()) {
                    delivery.setRemainingQuantity(previousRemaining);
                    this.pendingBuyTransfer = transfer;
                    target.inventory().setContents(before);
                    return;
                }
                this.lockedWarehouseIds.remove(record.id());
            }
        }
    }

    private void cleanupCommittedBuyDeliveries() {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return;
        }
        boolean settlementPending =
            this.plugin.getMatchSettlementJournal() != null
                && this.plugin.getMatchSettlementJournal().hasPending();
        if (settlementPending) {
            return;
        }
        Map<String, PendingBuyDelivery> previous =
            new LinkedHashMap<String, PendingBuyDelivery>(this.buyDeliveries);
        this.buyDeliveries.entrySet().removeIf(entry ->
            BuyDeliveryRetentionPolicy.canDiscard(
                entry.getValue().remainingQuantity(),
                false
            )
        );
        if (previous.size() == this.buyDeliveries.size()) {
            return;
        }
        if (!this.save()) {
            this.buyDeliveries.clear();
            this.buyDeliveries.putAll(previous);
            this.plugin.getLogger().warning(
                "[AssetAudit] Completed buy-delivery tombstones could not be compacted."
            );
        }
    }

    private void handleExistingConfiguration(
        Player player,
        WarehouseRecord existing,
        WarehouseType requestedType
    ) {
        if (!existing.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage("§c该交易仓库属于其他玩家，无法修改。");
            return;
        }
        if (existing.type() != requestedType) {
            String existingName = existing.type() == WarehouseType.SELL
                ? "自动出售仓库"
                : "求购收货仓库";
            player.sendMessage("§c该箱子已经是" + existingName + "，请在对应页面取消配置。");
            return;
        }
        if (!this.hasContainerAccess(player, existing.blocks())) {
            player.sendMessage("§c你没有该领地的容器使用权限，无法取消仓库配置。");
            return;
        }
        if (this.deactivateRecord(existing, true)) {
            player.sendMessage(existing.type() == WarehouseType.SELL
                ? "§a已取消自动出售仓库，剩余挂单已取消，箱内物品保持不变。"
                : "§a已取消求购收货仓库，箱内物品保持不变。");
        } else {
            player.sendMessage("§c仓库已停止交易，但部分旧挂单清理失败；系统会继续重试，请联系管理员。");
        }
    }

    private WarehouseRecord createRecord(
        Player player,
        ChestTarget target,
        WarehouseType type,
        BigDecimal price
    ) {
        String id = UUID.randomUUID().toString();
        Map<BlockKey, String> originalNames = new LinkedHashMap<BlockKey, String>();
        for (BlockKey blockKey : target.blocks()) {
            Block block = blockKey.resolveBlock(false);
            BlockState state = block == null ? null : block.getState();
            originalNames.put(
                blockKey,
                state instanceof Chest chest ? chest.getCustomName() : null
            );
        }
        WarehouseRecord record = new WarehouseRecord(
            id,
            type,
            player.getUniqueId(),
            player.getName(),
            price,
            true,
            System.currentTimeMillis(),
            new ArrayList<BlockKey>(target.blocks()),
            originalNames,
            new LinkedHashMap<String, Integer>()
        );
        this.records.put(id, record);
        this.indexActiveRecord(record);
        if (this.applyWarehouseMarker(record) && this.save()) {
            return record;
        }
        this.clearWarehouseMarker(record);
        this.removeActiveIndex(record);
        this.records.remove(id);
        return null;
    }

    private SyncResult syncSellWarehouse(WarehouseRecord record, boolean loadChunks) {
        if (record == null || !record.active() || record.type() != WarehouseType.SELL) {
            return SyncResult.EMPTY;
        }
        ChestTarget target = this.resolveRecordTarget(record, loadChunks);
        if (target == null) {
            if (loadChunks || this.areRecordChunksLoaded(record)) {
                this.deactivateForInvalidStructure(record);
            }
            return SyncResult.EMPTY;
        }
        this.applyWarehouseName(record);
        StockSnapshot stock = scanStock(target.inventory());
        int failedKinds = stock.invalidKinds();
        Set<String> handledItems = new HashSet<String>();
        Map<String, Integer> existingLinks =
            new LinkedHashMap<String, Integer>(record.sellOrders());
        for (Map.Entry<String, Integer> entry : existingLinks.entrySet()) {
            String itemBase64 = entry.getKey();
            int orderId = entry.getValue();
            int actualQuantity = stock.quantities().getOrDefault(itemBase64, 0);
            Order order = this.plugin.getStorageManager().getOrder(orderId);
            EscrowEntry escrow = this.plugin.getStorageManager()
                .getEscrow(orderId, EscrowEntry.AssetType.ITEM);
            boolean valid = order != null
                && order.isActive()
                && order.getOrderType() == Order.OrderType.SELL
                && record.ownerUuid().toString().equals(order.getPlayerUuid())
                && record.price() != null
                && record.price().compareTo(order.getPrice()) == 0
                && escrow != null
                && record.id().equals(order.getSourceWarehouseId())
                && record.id().equals(escrow.getSourceWarehouseId())
                && itemBase64.equals(escrow.getItemBase64());
            if (!valid) {
                if (!this.plugin.getOrderManager().cancelWarehouseSellOrder(
                        orderId,
                        record.id()
                    )) {
                    failedKinds++;
                    continue;
                }
                continue;
            }
            handledItems.add(itemBase64);
            if (actualQuantity <= 0) {
                if (!this.plugin.getOrderManager().cancelWarehouseSellOrder(
                    orderId,
                    record.id()
                )) {
                    failedKinds++;
                }
                continue;
            }
            if (WarehouseOrderState.needsResize(
                    order.getRemainingQty(),
                    escrow.getQuantity(),
                    actualQuantity
                )
                && !this.plugin.getOrderManager().resizeWarehouseSellOrder(
                    orderId,
                    record.id(),
                    actualQuantity
                )) {
                failedKinds++;
            }
        }
        for (Map.Entry<String, Integer> entry : stock.quantities().entrySet()) {
            String itemBase64 = entry.getKey();
            int quantity = entry.getValue();
            if (quantity <= 0 || handledItems.contains(itemBase64)
                || record.sellOrders().containsKey(itemBase64)) {
                continue;
            }
            ItemStack representative = stock.representatives().get(itemBase64);
            ExchangeItem exchangeItem = this.plugin.getItemManager()
                .resolveSpecialItem(representative);
            if (exchangeItem == null) {
                ItemManager.RegisterResult registerResult =
                    this.plugin.getItemManager().registerWarehouseCatalogItem(
                    record.ownerUuid().toString(),
                    record.ownerName(),
                    representative
                );
                exchangeItem = registerResult.isSuccess()
                    ? registerResult.getItem()
                    : null;
            }
            if (exchangeItem == null) {
                failedKinds++;
                continue;
            }
            int orderId = this.plugin.getOrderManager().createWarehouseSellOrder(
                record.ownerUuid().toString(),
                record.ownerName(),
                exchangeItem,
                itemBase64,
                record.price(),
                quantity,
                record.id()
            );
            if (orderId <= 0) {
                failedKinds++;
            }
        }
        return new SyncResult(failedKinds);
    }

    private boolean deactivateRecord(WarehouseRecord record, boolean restoreNames) {
        try {
            if (record == null) {
                return true;
            }
            if (this.isWarehouseLocked(record.id())) {
                return false;
            }
            boolean wasActive = record.active();
            record.setActive(false);
            this.removeActiveIndex(record);
            if (!this.save()) {
                record.setActive(wasActive);
                if (wasActive) {
                    this.indexActiveRecord(record);
                }
                return false;
            }
            boolean allCancelled = true;
            if (record.type() == WarehouseType.SELL) {
                for (Integer orderId :
                    new ArrayList<Integer>(record.sellOrders().values())) {
                    if (orderId != null && !this.plugin.getOrderManager()
                        .cancelWarehouseSellOrder(orderId, record.id())) {
                        allCancelled = false;
                    }
                }
            }
            boolean cleanupComplete = true;
            if (allCancelled && restoreNames) {
                cleanupComplete = this.restoreOriginalNames(record)
                    & this.clearWarehouseMarker(record);
            }
            if (!allCancelled || !cleanupComplete
                || !record.sellOrders().isEmpty()) {
                return false;
            }
            this.records.remove(record.id());
            boolean removedPersisted = false;
            try {
                removedPersisted = this.save();
                return removedPersisted;
            } finally {
                if (!removedPersisted) {
                    this.records.put(record.id(), record);
                }
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] WAREHOUSE_DEACTIVATION_ABORTED warehouse="
                    + (record == null ? "unknown" : record.id()),
                throwable
            );
            return false;
        }
    }

    private void deactivateForInvalidStructure(WarehouseRecord record) {
        if (record == null || !record.active()) {
            return;
        }
        this.plugin.getLogger().warning(
            "[Warehouse] Chest structure changed or disappeared; deactivating warehouse "
                + record.id()
        );
        Player owner = Bukkit.getPlayer(record.ownerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage("§e一个交易仓库的箱体结构已经变化，仓库已自动取消；"
                + "请站在当前箱子上重新配置。");
        }
        this.deactivateRecord(record, true);
    }

    private void reconcileLoadedWarehouses() {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this.plugin, this::reconcileLoadedWarehouses);
            return;
        }
        this.recoverPendingTransactions();
        for (WarehouseRecord record : new ArrayList<WarehouseRecord>(this.records.values())) {
            if (!record.active()) {
                if (this.mustRetainInactiveRecord(record)) {
                    continue;
                }
                this.deactivateRecord(record, true);
                continue;
            }
            if (this.isWarehouseLocked(record.id())) {
                continue;
            }
            if (!this.areRecordChunksLoaded(record)) {
                continue;
            }
            if (record.type() == WarehouseType.SELL) {
                this.syncSellWarehouse(record, false);
            } else {
                ChestTarget target = this.resolveRecordTarget(record, false);
                if (target == null) {
                    this.deactivateForInvalidStructure(record);
                } else {
                    this.applyWarehouseName(record);
                    this.routePendingBuyDeliveries(record.ownerUuid());
                }
            }
        }
    }

    private void scheduleSync(String warehouseId) {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()
            || warehouseId == null
            || !this.scheduledWarehouseSyncs.add(warehouseId)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.scheduledWarehouseSyncs.remove(warehouseId);
            if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
                return;
            }
            WarehouseRecord record = this.records.get(warehouseId);
            if (record == null || !record.active()) {
                return;
            }
            if (this.isWarehouseLocked(record.id())) {
                return;
            }
            if (record.type() == WarehouseType.SELL) {
                this.syncSellWarehouse(record, false);
            } else {
                this.routePendingBuyDeliveries(record.ownerUuid());
            }
        }, EVENT_SYNC_DELAY_TICKS);
    }

    private void scheduleByInventory(Inventory inventory) {
        for (String warehouseId : this.findWarehouseIds(inventory)) {
            WarehouseRecord record = this.records.get(warehouseId);
            if (record != null && record.type() == WarehouseType.BUY) {
                this.scheduleSync(warehouseId);
            }
        }
    }

    private Set<String> findWarehouseIds(Inventory inventory) {
        Set<String> ids = new LinkedHashSet<String>();
        if (inventory == null) {
            return ids;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            this.addWarehouseId(ids, chest);
        } else if (holder instanceof DoubleChest doubleChest) {
            this.addWarehouseId(ids, doubleChest.getLeftSide());
            this.addWarehouseId(ids, doubleChest.getRightSide());
        }
        return ids;
    }

    private void addWarehouseId(Set<String> ids, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            BlockKey block = BlockKey.of(chest.getLocation());
            String id = this.activeWarehouseByBlock.get(block);
            if (id == null) {
                id = chest.getPersistentDataContainer().get(
                    this.warehouseIdKey,
                    PersistentDataType.STRING
                );
            }
            if (id != null) {
                ids.add(id);
            }
            for (WarehouseRecord record : this.records.values()) {
                if (record.blocks().contains(block)) {
                    ids.add(record.id());
                }
            }
        }
    }

    private ChestTarget resolveStandingChest(Player player) {
        Block block = player.getLocation().getBlock();
        if (!(block.getState() instanceof Chest)) {
            return null;
        }
        return this.resolveTarget(BlockKey.of(block.getLocation()), false);
    }

    private ChestTarget resolveRecordTarget(WarehouseRecord record, boolean loadChunks) {
        if (record == null || record.blocks().isEmpty()) {
            return null;
        }
        if (loadChunks && !this.loadAllRecordChunks(record)) {
            return null;
        }
        ChestTarget target = this.resolveTarget(record.blocks().get(0), false);
        return target != null
            && sameBlocks(target.blocks(), record.blocks())
            && this.targetHasWarehouseMarker(target, record)
            ? target
            : null;
    }

    private ChestTarget resolveTarget(BlockKey primary, boolean loadChunk) {
        Block block = primary.resolveBlock(loadChunk);
        if (block == null || !(block.getState() instanceof Chest chest)) {
            return null;
        }
        Inventory inventory = chest.getInventory();
        InventoryHolder holder = inventory.getHolder();
        List<BlockKey> blocks = new ArrayList<BlockKey>();
        if (holder instanceof DoubleChest doubleChest) {
            if (!(doubleChest.getLeftSide() instanceof Chest left)
                || !(doubleChest.getRightSide() instanceof Chest right)) {
                return null;
            }
            blocks.add(BlockKey.of(left.getLocation()));
            blocks.add(BlockKey.of(right.getLocation()));
            inventory = doubleChest.getInventory();
        } else {
            blocks.add(BlockKey.of(chest.getLocation()));
            inventory = chest.getBlockInventory();
        }
        blocks.sort(BlockKey.ORDER);
        return new ChestTarget(blocks, inventory);
    }

    private ConfigurationTargetCheck inspectConfigurationTarget(ChestTarget target) {
        if (target == null || target.blocks().isEmpty()) {
            return ConfigurationTargetCheck.blocked(NO_CHEST_MESSAGE);
        }
        List<WarehouseRecord> overlapping = new ArrayList<WarehouseRecord>();
        for (WarehouseRecord record : this.records.values()) {
            boolean overlaps = false;
            for (BlockKey block : target.blocks()) {
                if (record.blocks().contains(block)) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                overlapping.add(record);
            }
        }
        if (overlapping.size() > 1) {
            return ConfigurationTargetCheck.blocked(
                "§c这个箱子存在冲突的仓库记录，请联系管理员处理。"
            );
        }
        WarehouseRecord record = overlapping.isEmpty() ? null : overlapping.get(0);
        if (record != null && !sameBlocks(record.blocks(), target.blocks())) {
            return ConfigurationTargetCheck.blocked(
                "§c这个大箱子的两侧存在部分或冲突的仓库记录，请联系管理员处理。"
            );
        }
        if (record != null && !record.active()) {
            if (this.mustRetainInactiveRecord(record)
                || !this.deactivateRecord(record, true)) {
                return ConfigurationTargetCheck.blocked(
                    "§c旧仓库记录仍在结算或清理中，暂时不能覆盖，请稍后重试。"
                );
            }
            return ConfigurationTargetCheck.blocked(
                "§e已清理这个箱子的旧仓库记录，请再次点击配置按钮。"
            );
        }

        String markerId = null;
        String markerType = null;
        int markedBlocks = 0;
        for (BlockKey blockKey : target.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block == null || !(block.getState() instanceof TileState tileState)) {
                return ConfigurationTargetCheck.blocked(
                    "§c无法完整读取箱体标记，已拒绝覆盖，请联系管理员处理。"
                );
            }
            String storedId = tileState.getPersistentDataContainer().get(
                this.warehouseIdKey,
                PersistentDataType.STRING
            );
            String storedType = tileState.getPersistentDataContainer().get(
                this.warehouseTypeKey,
                PersistentDataType.STRING
            );
            if ((storedId == null) != (storedType == null)) {
                return ConfigurationTargetCheck.blocked(
                    "§c这个箱子存在不完整的仓库标记，已拒绝覆盖，请联系管理员处理。"
                );
            }
            if (storedId == null) {
                continue;
            }
            markedBlocks++;
            if (markerId == null) {
                markerId = storedId;
                markerType = storedType;
            } else if (!markerId.equals(storedId) || !markerType.equals(storedType)) {
                return ConfigurationTargetCheck.blocked(
                    "§c这个大箱子的两侧存在冲突的仓库标记，已拒绝覆盖，请联系管理员处理。"
                );
            }
        }
        if (markedBlocks > 0 && markedBlocks != target.blocks().size()) {
            return ConfigurationTargetCheck.blocked(
                "§c这个大箱子只有部分箱体带有仓库标记，已拒绝覆盖，请联系管理员处理。"
            );
        }
        if (markerId != null) {
            WarehouseRecord markedRecord = this.records.get(markerId);
            if (markedRecord == null
                || !markedRecord.active()
                || !sameBlocks(markedRecord.blocks(), target.blocks())
                || !markedRecord.type().name().equals(markerType)
                || (record != null && !record.id().equals(markerId))) {
                return ConfigurationTargetCheck.blocked(
                    "§c这个箱子存在孤立或冲突的仓库标记，已拒绝覆盖，请联系管理员处理。"
                );
            }
            return ConfigurationTargetCheck.existing(markedRecord);
        }
        if (record != null) {
            return ConfigurationTargetCheck.blocked(
                "§c该仓库记录缺少完整箱体标记，已拒绝覆盖，请联系管理员处理。"
            );
        }
        return ConfigurationTargetCheck.availableTarget();
    }

    private boolean mustRetainInactiveRecord(WarehouseRecord record) {
        if (record == null) {
            return false;
        }
        if (this.isWarehouseLocked(record.id())
            || (this.pendingSaleTransaction != null
                && record.id().equals(this.pendingSaleTransaction.warehouseId()))
            || (this.pendingBuyTransfer != null
                && record.id().equals(this.pendingBuyTransfer.warehouseId()))) {
            return true;
        }
        return false;
    }

    private boolean loadAllRecordChunks(WarehouseRecord record) {
        for (BlockKey block : record.blocks()) {
            if (block.resolveBlock(true) == null) {
                return false;
            }
        }
        return true;
    }

    private List<WarehouseRecord> getActiveWarehouses(
        UUID ownerUuid,
        WarehouseType type
    ) {
        List<WarehouseRecord> result = new ArrayList<WarehouseRecord>();
        for (WarehouseRecord record : this.records.values()) {
            if (record.active()
                && record.type() == type
                && ownerUuid.equals(record.ownerUuid())) {
                result.add(record);
            }
        }
        result.sort(Comparator.comparingLong(WarehouseRecord::createdAt)
            .thenComparing(WarehouseRecord::id));
        return result;
    }

    private boolean hasContainerAccess(Player player, List<BlockKey> blocks) {
        Plugin residencePlugin = Bukkit.getPluginManager().getPlugin("Residence");
        if (residencePlugin == null || !residencePlugin.isEnabled()) {
            return true;
        }
        try {
            if (ResidenceApi.getResidenceManager() == null) {
                this.plugin.getLogger().warning(
                    "Residence is enabled but its API is not ready; warehouse configuration denied."
                );
                return false;
            }
            for (BlockKey blockKey : blocks) {
                Location location = blockKey.resolveLocation(false);
                if (location == null) {
                    return false;
                }
                ClaimedResidence claimed =
                    ResidenceApi.getResidenceManager().getByLoc(location);
                if (claimed != null
                    && !claimed.getPermissions().playerHas(
                        player,
                        Flags.container,
                        true
                    )) {
                    return false;
                }
            }
            return true;
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(
                Level.WARNING,
                "Failed to query Residence container permission; warehouse configuration denied.",
                throwable
            );
            return false;
        }
    }

    private void applyWarehouseName(WarehouseRecord record) {
        if (record == null || !record.active()) {
            return;
        }
        String name = record.type() == WarehouseType.SELL
            ? record.ownerName() + "的出售仓库，单价：" + formatPrice(record.price())
            : record.ownerName() + "的求购收货仓库";
        for (BlockKey blockKey : record.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block != null && block.getState() instanceof Chest chest
                && !name.equals(chest.getCustomName())) {
                chest.setCustomName(name);
                chest.update(true, false);
            }
        }
    }

    private boolean applyWarehouseMarker(WarehouseRecord record) {
        if (record == null) {
            return false;
        }
        List<Chest> updated = new ArrayList<Chest>();
        for (BlockKey blockKey : record.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block == null || !(block.getState() instanceof Chest chest)) {
                for (Chest previous : updated) {
                    previous.getPersistentDataContainer().remove(this.warehouseIdKey);
                    previous.getPersistentDataContainer().remove(this.warehouseTypeKey);
                    previous.update(true, false);
                }
                return false;
            }
            chest.getPersistentDataContainer().set(
                this.warehouseIdKey,
                PersistentDataType.STRING,
                record.id()
            );
            chest.getPersistentDataContainer().set(
                this.warehouseTypeKey,
                PersistentDataType.STRING,
                record.type().name()
            );
            if (!chest.update(true, false)) {
                for (Chest previous : updated) {
                    previous.getPersistentDataContainer().remove(this.warehouseIdKey);
                    previous.getPersistentDataContainer().remove(this.warehouseTypeKey);
                    previous.update(true, false);
                }
                return false;
            }
            updated.add(chest);
        }
        return true;
    }

    private boolean clearWarehouseMarker(WarehouseRecord record) {
        if (record == null) {
            return true;
        }
        boolean cleared = true;
        for (BlockKey blockKey : record.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block != null && block.getState() instanceof TileState tileState) {
                tileState.getPersistentDataContainer().remove(this.warehouseIdKey);
                tileState.getPersistentDataContainer().remove(this.warehouseTypeKey);
                if (!tileState.update(true, false)) {
                    cleared = false;
                    continue;
                }
                BlockState updatedState = block.getState();
                if (!(updatedState instanceof TileState updated)
                    || updated.getPersistentDataContainer().has(
                        this.warehouseIdKey,
                        PersistentDataType.STRING
                    )
                    || updated.getPersistentDataContainer().has(
                        this.warehouseTypeKey,
                        PersistentDataType.STRING
                    )) {
                    cleared = false;
                }
            }
        }
        return cleared;
    }

    private boolean targetHasWarehouseMarker(
        ChestTarget target,
        WarehouseRecord record
    ) {
        if (target == null || record == null) {
            return false;
        }
        for (BlockKey blockKey : target.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block == null || !(block.getState() instanceof TileState tileState)) {
                return false;
            }
            String storedId = tileState.getPersistentDataContainer().get(
                this.warehouseIdKey,
                PersistentDataType.STRING
            );
            String storedType = tileState.getPersistentDataContainer().get(
                this.warehouseTypeKey,
                PersistentDataType.STRING
            );
            if (!record.id().equals(storedId)
                || !record.type().name().equals(storedType)) {
                return false;
            }
        }
        return true;
    }

    private boolean restoreOriginalNames(WarehouseRecord record) {
        boolean restored = true;
        for (BlockKey blockKey : record.blocks()) {
            Block block = blockKey.resolveBlock(false);
            if (block != null && block.getState() instanceof Chest chest) {
                String originalName = record.originalNames().get(blockKey);
                chest.setCustomName(originalName);
                if (!chest.update(true, false)) {
                    restored = false;
                    continue;
                }
                BlockState updatedState = block.getState();
                if (!(updatedState instanceof Chest updated)
                    || !Objects.equals(originalName, updated.getCustomName())) {
                    restored = false;
                }
            }
        }
        return restored;
    }

    private boolean areRecordChunksLoaded(WarehouseRecord record) {
        for (BlockKey block : record.blocks()) {
            World world = block.resolveWorld();
            if (world == null || !world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
                return false;
            }
        }
        return true;
    }

    private void indexActiveRecord(WarehouseRecord record) {
        if (record == null || !record.active()) {
            return;
        }
        for (BlockKey block : record.blocks()) {
            this.activeWarehouseByBlock.put(block, record.id());
        }
    }

    private void removeActiveIndex(WarehouseRecord record) {
        if (record == null) {
            return;
        }
        for (BlockKey block : record.blocks()) {
            this.activeWarehouseByBlock.remove(block, record.id());
        }
    }

    private void rebuildIndexesAndRecoverLinks() {
        this.activeWarehouseByBlock.clear();
        this.sellOrderLinks.clear();
        for (WarehouseRecord record : this.records.values()) {
            if (record.active()) {
                for (BlockKey block : record.blocks()) {
                    String previous = this.activeWarehouseByBlock.putIfAbsent(
                        block,
                        record.id()
                    );
                    if (previous != null && !previous.equals(record.id())) {
                        throw new IllegalStateException(
                            "physical warehouses overlap at " + block
                        );
                    }
                }
            }
            for (Map.Entry<String, Integer> entry : record.sellOrders().entrySet()) {
                OrderLink link = new OrderLink(record.id(), entry.getKey());
                OrderLink previous = this.sellOrderLinks.putIfAbsent(
                    entry.getValue(),
                    link
                );
                if (previous != null && !previous.equals(link)) {
                    throw new IllegalStateException(
                        "warehouse order " + entry.getValue()
                            + " has conflicting physical links"
                    );
                }
            }
        }
        boolean changed = false;
        for (WarehouseRecord record : this.records.values()) {
            if (record.type() != WarehouseType.SELL) {
                continue;
            }
            if (this.mustRetainInactiveRecord(record)) {
                continue;
            }
            Map<String, List<Order>> validOrdersByItem =
                new LinkedHashMap<String, List<Order>>();
            List<EscrowEntry> warehouseEscrows = this.plugin.getStorageManager()
                .getEscrowsBySourceWarehouse(record.id());
            List<Integer> escrowOrderIds = new ArrayList<Integer>();
            for (EscrowEntry escrow : warehouseEscrows) {
                if (escrow.getAssetType() != EscrowEntry.AssetType.ITEM) {
                    throw new IllegalStateException(
                        "warehouse " + record.id()
                            + " has an unexpected non-item escrow "
                            + escrow.getOrderId()
                    );
                }
                escrowOrderIds.add(escrow.getOrderId());
            }
            List<Order> sourceOrders = this.plugin.getStorageManager()
                .getOrdersBySourceWarehouse(record.id());
            List<Integer> sourceOrderIds = new ArrayList<Integer>();
            for (Order order : sourceOrders) {
                sourceOrderIds.add(order.getId());
            }
            Set<Integer> referencedOrderIds =
                WarehouseOrderRecoveryPolicy.collectReferencedIds(
                    record.sellOrders().values(),
                    escrowOrderIds,
                    sourceOrderIds
                );
            for (Integer orderId : referencedOrderIds) {
                Order order = this.plugin.getStorageManager().getOrder(orderId);
                EscrowEntry escrow = this.plugin.getStorageManager()
                    .getEscrow(orderId, EscrowEntry.AssetType.ITEM);
                if (order != null
                    && order.getOrderType() != Order.OrderType.SELL) {
                    throw new IllegalStateException(
                        "warehouse " + record.id()
                            + " order " + orderId
                            + " is not a SELL order"
                    );
                }
                if (order != null
                    && !record.id().equals(order.getSourceWarehouseId())) {
                    throw new IllegalStateException(
                        "warehouse " + record.id()
                            + " links conflicting order " + orderId
                    );
                }
                if (escrow != null
                    && !record.id().equals(escrow.getSourceWarehouseId())) {
                    throw new IllegalStateException(
                        "warehouse " + record.id()
                            + " links conflicting escrow " + orderId
                    );
                }
                if (!this.isRecoverableWarehouseOrder(record, order, escrow)) {
                    if (!this.plugin.getOrderManager().cancelWarehouseSellOrder(
                            orderId,
                            record.id()
                        )) {
                        throw new IllegalStateException(
                            "failed to cancel invalid warehouse order "
                                + orderId
                        );
                    }
                    this.plugin.getLogger().warning(
                        "[AssetAudit] WAREHOUSE_RECOVERY_CANCELLED_INVALID warehouse="
                            + record.id() + " order=" + orderId
                    );
                    changed = true;
                    continue;
                }
                validOrdersByItem.computeIfAbsent(
                    escrow.getItemBase64(),
                    ignored -> new ArrayList<Order>()
                ).add(order);
            }

            Map<String, Integer> recoveredOrders =
                new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, List<Order>> entry :
                validOrdersByItem.entrySet()) {
                String itemBase64 = entry.getKey();
                List<Order> candidates = entry.getValue();
                if (!record.active()) {
                    for (Order order : candidates) {
                        if (!this.plugin.getOrderManager()
                            .cancelWarehouseSellOrder(
                                order.getId(),
                                record.id()
                            )) {
                            throw new IllegalStateException(
                                "failed to cancel inactive warehouse order "
                                    + order.getId()
                            );
                        }
                        changed = true;
                    }
                    continue;
                }
                Set<Integer> candidateIds = new LinkedHashSet<Integer>();
                for (Order order : candidates) {
                    candidateIds.add(order.getId());
                }
                Set<Integer> lockedIds = this.getLockedRecoveryOrderIds(
                    record,
                    itemBase64,
                    candidateIds
                );
                int authoritativeOrderId =
                    WarehouseOrderRecoveryPolicy.chooseAuthoritative(
                        candidateIds,
                        record.sellOrders().get(itemBase64),
                        lockedIds
                    );
                recoveredOrders.put(itemBase64, authoritativeOrderId);
                for (Order order : candidates) {
                    if (order.getId() == authoritativeOrderId) {
                        continue;
                    }
                    if (!this.plugin.getOrderManager().cancelWarehouseSellOrder(
                            order.getId(),
                            record.id()
                        )) {
                        throw new IllegalStateException(
                            "failed to cancel duplicate warehouse order "
                                + order.getId()
                        );
                    }
                    this.plugin.getLogger().warning(
                        "[AssetAudit] WAREHOUSE_RECOVERY_CANCELLED_DUPLICATE warehouse="
                            + record.id() + " kept=" + authoritativeOrderId
                            + " cancelled=" + order.getId()
                    );
                    changed = true;
                }
            }
            if (!record.sellOrders().equals(recoveredOrders)) {
                record.sellOrders().clear();
                record.sellOrders().putAll(recoveredOrders);
                changed = true;
            }
        }

        this.sellOrderLinks.clear();
        for (WarehouseRecord record : this.records.values()) {
            for (Map.Entry<String, Integer> entry :
                record.sellOrders().entrySet()) {
                OrderLink link = new OrderLink(record.id(), entry.getKey());
                OrderLink previous = this.sellOrderLinks.putIfAbsent(
                    entry.getValue(),
                    link
                );
                if (previous != null && !previous.equals(link)) {
                    throw new IllegalStateException(
                        "recovered warehouse order " + entry.getValue()
                            + " has conflicting physical links"
                    );
                }
            }
        }
        if (changed && !this.save()) {
            throw new IllegalStateException(
                "failed to persist recovered physical warehouse links"
            );
        }
    }

    private boolean isRecoverableWarehouseOrder(
        WarehouseRecord record,
        Order order,
        EscrowEntry escrow
    ) {
        return record != null && record.active()
            && record.price() != null
            && order != null && order.isActive()
            && order.getOrderType() == Order.OrderType.SELL
            && record.ownerUuid().toString().equals(order.getPlayerUuid())
            && record.id().equals(order.getSourceWarehouseId())
            && record.price().compareTo(order.getPrice()) == 0
            && escrow != null
            && escrow.getAssetType() == EscrowEntry.AssetType.ITEM
            && record.ownerUuid().toString().equals(escrow.getPlayerUuid())
            && record.id().equals(escrow.getSourceWarehouseId())
            && escrow.getItemBase64() != null
            && !escrow.getItemBase64().isBlank()
            && escrow.getQuantity() == order.getRemainingQty()
            && escrow.getQuantity() > 0
            && this.journalItemMatchesOrder(order, escrow.getItemBase64());
    }

    private Set<Integer> getLockedRecoveryOrderIds(
        WarehouseRecord record,
        String itemBase64,
        Set<Integer> candidateIds
    ) {
        Set<Integer> lockedIds = new LinkedHashSet<Integer>();
        if (this.pendingSaleTransaction != null
            && record.id().equals(this.pendingSaleTransaction.warehouseId())
            && itemBase64.equals(this.pendingSaleTransaction.itemBase64())
            && candidateIds.contains(this.pendingSaleTransaction.orderId())) {
            lockedIds.add(this.pendingSaleTransaction.orderId());
        }
        if (this.plugin.getMatchSettlementJournal() != null) {
            int settlementOrderId = this.plugin.getMatchSettlementJournal()
                .current() == null
                    ? -1
                    : this.plugin.getMatchSettlementJournal()
                        .current()
                        .sellOrderId();
            if (candidateIds.contains(settlementOrderId)) {
                lockedIds.add(settlementOrderId);
            }
        }
        return lockedIds;
    }

    private WarehouseRecord loadWarehouseRecord(
        String id,
        ConfigurationSection section
    ) {
        WarehouseJournalValidation.requireUuid(id, "warehouse id");
        WarehouseType type = WarehouseType.valueOf(
            requireString(section, "type")
        );
        UUID ownerUuid = UUID.fromString(requireString(section, "owner-uuid"));
        String ownerName = section.contains("owner-name")
            ? requireString(section, "owner-name")
            : ownerUuid.toString();
        if (ownerName.isBlank()) {
            ownerName = ownerUuid.toString();
        }
        BigDecimal price = null;
        if (type == WarehouseType.SELL) {
            price = new BigDecimal(requireString(section, "price"));
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                    "SELL warehouse price must be positive"
                );
            }
        }
        boolean active = section.contains("active")
            ? requireBoolean(section, "active")
            : true;
        long createdAt = section.contains("created-at")
            ? requireLong(section, "created-at")
            : 0L;

        List<BlockKey> blocks = new ArrayList<BlockKey>();
        Map<BlockKey, String> originalNames =
            new LinkedHashMap<BlockKey, String>();
        for (Object rawBlockValue : requireList(section, "blocks")) {
            if (!(rawBlockValue instanceof Map<?, ?> rawBlock)) {
                throw new IllegalArgumentException(
                    "warehouse blocks contains a non-map entry"
                );
            }
            BlockKey block = BlockKey.fromMap(rawBlock);
            if (block == null) {
                throw new IllegalArgumentException(
                    "warehouse blocks contains an invalid block"
                );
            }
            if (originalNames.containsKey(block)) {
                throw new IllegalArgumentException(
                    "warehouse blocks contains a duplicate block"
                );
            }
            blocks.add(block);
            Object originalName = rawBlock.get("original-name");
            if (originalName != null && !(originalName instanceof String)) {
                throw new IllegalArgumentException(
                    "warehouse block original-name must be a string"
                );
            }
            originalNames.put(block, (String)originalName);
        }
        blocks.sort(BlockKey.ORDER);
        if (blocks.isEmpty() || blocks.size() > 2) {
            throw new IllegalArgumentException("invalid chest block count");
        }

        Map<String, Integer> sellOrders = new LinkedHashMap<String, Integer>();
        Set<Integer> mappedOrderIds = new HashSet<Integer>();
        for (Object rawOrderValue : optionalList(section, "sell-orders")) {
            if (!(rawOrderValue instanceof Map<?, ?> rawOrder)) {
                throw new IllegalArgumentException(
                    "warehouse sell-orders contains a non-map entry"
                );
            }
            String itemBase64 = WarehouseJournalValidation.requireNonBlank(
                rawOrder.get("item-base64"),
                "warehouse sell-order item-base64"
            );
            int orderId = WarehouseJournalValidation.requirePositiveInt(
                rawOrder.get("order-id"),
                "warehouse sell-order order-id"
            );
            if (sellOrders.put(itemBase64, orderId) != null
                || !mappedOrderIds.add(orderId)) {
                throw new IllegalArgumentException(
                    "warehouse sell-orders contains a duplicate mapping"
                );
            }
        }
        if (type != WarehouseType.SELL && !sellOrders.isEmpty()) {
            throw new IllegalArgumentException(
                "BUY warehouse cannot contain sell-order mappings"
            );
        }
        return new WarehouseRecord(
            id,
            type,
            ownerUuid,
            ownerName,
            price,
            active,
            createdAt,
            blocks,
            originalNames,
            sellOrders
        );
    }

    private PendingBuyDelivery loadBuyDelivery(Map<?, ?> rawDelivery) {
        String key = WarehouseJournalValidation.requireNonBlank(
            rawDelivery.get("key"),
            "buy delivery key"
        );
        UUID ownerUuid = UUID.fromString(
            WarehouseJournalValidation.requireUuid(
                WarehouseJournalValidation.requireNonBlank(
                    rawDelivery.get("owner-uuid"),
                    "buy delivery owner-uuid"
                ),
                "buy delivery owner-uuid"
            )
        );
        String itemBase64 = WarehouseJournalValidation.requireNonBlank(
            rawDelivery.get("item-base64"),
            "buy delivery item-base64"
        );
        int originalQuantity = WarehouseJournalValidation.requirePositiveInt(
            rawDelivery.get("original-quantity"),
            "buy delivery original-quantity"
        );
        int remainingQuantity = WarehouseJournalValidation.requireNonNegativeInt(
            rawDelivery.get("remaining-quantity"),
            "buy delivery remaining-quantity"
        );
        long createdAt = WarehouseJournalValidation.requireNonNegativeLong(
            rawDelivery.get("created-at"),
            "buy delivery created-at"
        );
        if (remainingQuantity > originalQuantity) {
            throw new IllegalArgumentException(
                "buy delivery remaining quantity exceeds original quantity"
            );
        }
        return new PendingBuyDelivery(
            key,
            ownerUuid,
            itemBase64,
            originalQuantity,
            remainingQuantity,
            createdAt
        );
    }

    private PendingSaleTransaction loadSaleJournal(
        ConfigurationSection saleJournal
    ) {
        return new PendingSaleTransaction(
            requireString(saleJournal, "id"),
            requireString(saleJournal, "warehouse-id"),
            requireInt(saleJournal, "order-id"),
            requireInt(saleJournal, "buy-order-id"),
            requireInt(saleJournal, "expected-filled-quantity"),
            requireInt(saleJournal, "expected-buy-filled-quantity"),
            requireString(saleJournal, "item-base64"),
            requireInt(saleJournal, "quantity"),
            requireStringList(saleJournal, "before"),
            requireStringList(saleJournal, "after"),
            WarehouseSaleRecoveryPolicy.Decision.valueOf(
                requireString(saleJournal, "decision")
            ),
            optionalString(saleJournal, "reason")
        );
    }

    private PendingBuyTransfer loadBuyJournal(
        ConfigurationSection buyJournal
    ) {
        return new PendingBuyTransfer(
            requireString(buyJournal, "id"),
            requireString(buyJournal, "delivery-key"),
            requireString(buyJournal, "warehouse-id"),
            requireInt(buyJournal, "quantity"),
            requireStringList(buyJournal, "before"),
            requireStringList(buyJournal, "after")
        );
    }

    private void validateLoadedSaleJournal(PendingSaleTransaction transaction) {
        WarehouseRecord record = this.records.get(transaction.warehouseId());
        if (record == null || record.type() != WarehouseType.SELL) {
            throw new IllegalStateException(
                "sale journal does not reference an existing SELL warehouse"
            );
        }
        Order sellOrder = this.plugin.getStorageManager().getOrder(
            transaction.orderId()
        );
        Order buyOrder = this.plugin.getStorageManager().getOrder(
            transaction.buyOrderId()
        );
        if (sellOrder == null || buyOrder == null) {
            throw new IllegalStateException(
                "sale journal references a missing order"
            );
        }
        if (sellOrder.getOrderType() != Order.OrderType.SELL
            || buyOrder.getOrderType() != Order.OrderType.BUY
            || sellOrder.getItemId() != buyOrder.getItemId()
            || !this.journalItemMatchesOrder(
                sellOrder,
                transaction.itemBase64()
            )) {
            throw new IllegalStateException(
                "sale journal order type or item relationship is invalid"
            );
        }
        if (!transaction.warehouseId().equals(
                sellOrder.getSourceWarehouseId()
            )
            || (buyOrder.getSourceWarehouseId() != null
                && !buyOrder.getSourceWarehouseId().isBlank())
            || !record.ownerUuid().toString().equals(
                sellOrder.getPlayerUuid()
            )) {
            throw new IllegalStateException(
                "sale journal order source relationship is invalid"
            );
        }
        EscrowEntry sellEscrow = this.plugin.getStorageManager().getEscrow(
            sellOrder.getId(),
            EscrowEntry.AssetType.ITEM
        );
        if (sellEscrow != null
            && (!sellOrder.getPlayerUuid().equals(sellEscrow.getPlayerUuid())
                || !transaction.warehouseId().equals(
                    sellEscrow.getSourceWarehouseId()
                )
                || !transaction.itemBase64().equals(
                    sellEscrow.getItemBase64()
                ))) {
            throw new IllegalStateException(
                "sale journal conflicts with the persisted item escrow"
            );
        }
        if (sellOrder.getPlayerUuid().equals(buyOrder.getPlayerUuid())) {
            throw new IllegalStateException(
                "sale journal cannot reference a self trade"
            );
        }
        if (transaction.expectedFilledQuantity() > sellOrder.getQuantity()
            || transaction.expectedBuyFilledQuantity() > buyOrder.getQuantity()
            || !isExpectedJournalFill(
                sellOrder.getFilledQty(),
                transaction.expectedFilledQuantity(),
                transaction.quantity()
            )
            || !isExpectedJournalFill(
                buyOrder.getFilledQty(),
                transaction.expectedBuyFilledQuantity(),
                transaction.quantity()
            )) {
            throw new IllegalStateException(
                "sale journal expected filled quantities are inconsistent"
            );
        }
        if (!WarehouseJournalValidation.isRecoveryStateCompatible(
                transaction.decision(),
                sellOrder.getFilledQty(),
                transaction.expectedFilledQuantity(),
                buyOrder.getFilledQty(),
                transaction.expectedBuyFilledQuantity(),
                transaction.quantity()
            )) {
            throw new IllegalStateException(
                "sale journal decision conflicts with persisted order progress"
            );
        }
        Integer authoritativeOrderId = record.sellOrders().get(
            transaction.itemBase64()
        );
        if (authoritativeOrderId == null
            || authoritativeOrderId.intValue() != transaction.orderId()) {
            throw new IllegalStateException(
                "sale journal is not present in the authoritative warehouse mapping"
            );
        }
        for (Map.Entry<String, Integer> entry : record.sellOrders().entrySet()) {
            if (entry.getValue() != null
                && entry.getValue().intValue() == transaction.orderId()
                && !entry.getKey().equals(transaction.itemBase64())) {
                throw new IllegalStateException(
                    "sale journal order has conflicting warehouse mappings"
                );
            }
        }
        requireExactSaleSnapshotDelta(transaction);
    }

    private boolean journalItemMatchesOrder(
        Order order,
        String itemBase64
    ) {
        if (order == null || itemBase64 == null || itemBase64.isBlank()) {
            return false;
        }
        ExchangeItem exchangeItem = this.plugin.getItemManager().getItem(
            order.getItemId()
        );
        ItemStack journalItem = ItemSerializer.itemFromBase64(itemBase64);
        ItemStack catalogItem = exchangeItem == null
            ? null
            : ItemSerializer.itemFromBase64(exchangeItem.getItemBase64());
        if (exchangeItem == null || journalItem == null || catalogItem == null
            || journalItem.getType().isAir()
            || catalogItem.getType().isAir()) {
            return false;
        }
        SpecialCategory journalCategory = SpecialCategory.of(journalItem);
        SpecialCategory catalogCategory = SpecialCategory.of(catalogItem);
        if (journalCategory != null || catalogCategory != null) {
            return journalCategory != null
                && journalCategory == catalogCategory;
        }
        ItemStack normalizedJournal = ItemSerializer.copyWithoutCustomName(
            journalItem
        );
        ItemStack normalizedCatalog = ItemSerializer.copyWithoutCustomName(
            catalogItem
        );
        return normalizedJournal != null && normalizedCatalog != null
            && normalizedJournal.isSimilar(normalizedCatalog);
    }

    private void validateLoadedBuyJournal(PendingBuyTransfer transfer) {
        WarehouseRecord record = this.records.get(transfer.warehouseId());
        PendingBuyDelivery delivery = this.buyDeliveries.get(
            transfer.deliveryKey()
        );
        if (record == null || record.type() != WarehouseType.BUY) {
            throw new IllegalStateException(
                "buy journal does not reference an existing BUY warehouse"
            );
        }
        if (delivery == null) {
            throw new IllegalStateException(
                "buy journal references a missing delivery"
            );
        }
        if (!record.ownerUuid().equals(delivery.ownerUuid())
            || transfer.quantity() > delivery.remainingQuantity()) {
            throw new IllegalStateException(
                "buy journal owner or quantity relationship is invalid"
            );
        }
        requireExactBuySnapshotDelta(transfer, delivery);
    }

    private static boolean isExpectedJournalFill(
        int currentFilled,
        int expectedFilled,
        int quantity
    ) {
        int beforeFilled = expectedFilled - quantity;
        return currentFilled == beforeFilled || currentFilled == expectedFilled;
    }

    private static void requireExactSaleSnapshotDelta(
        PendingSaleTransaction transaction
    ) {
        int size = transaction.before().size();
        ItemStack[] before = deserializeContents(transaction.before(), size);
        ItemStack[] after = deserializeContents(transaction.after(), size);
        ItemStack saleItem = ItemSerializer.itemFromBase64(
            transaction.itemBase64()
        );
        if (before == null || after == null || saleItem == null
            || saleItem.getType().isAir()) {
            throw new IllegalArgumentException(
                "sale journal contains an unreadable item or inventory snapshot"
            );
        }
        if (!isExactSaleContentsDelta(
                before,
                after,
                saleItem,
                transaction.quantity()
            )) {
            throw new IllegalArgumentException(
                "sale journal snapshot delta does not exactly remove the sale item"
            );
        }
    }

    private static void requireExactBuySnapshotDelta(
        PendingBuyTransfer transfer,
        PendingBuyDelivery delivery
    ) {
        int size = transfer.before().size();
        ItemStack[] before = deserializeContents(transfer.before(), size);
        ItemStack[] after = deserializeContents(transfer.after(), size);
        ItemStack baseItem = ItemSerializer.itemFromBase64(
            delivery.itemBase64()
        );
        if (before == null || after == null || baseItem == null
            || baseItem.getType().isAir()) {
            throw new IllegalArgumentException(
                "buy journal contains an unreadable item or inventory snapshot"
            );
        }
        if (!isExactBuyContentsDelta(
                before,
                after,
                baseItem,
                transfer.quantity()
            )) {
            throw new IllegalArgumentException(
                "buy journal snapshot delta does not exactly add the delivery item"
            );
        }
    }

    private static boolean isExactSaleContentsDelta(
        ItemStack[] before,
        ItemStack[] after,
        ItemStack saleItem,
        int quantity
    ) {
        ItemStack[] expectedAfter = cloneContents(before);
        return expectedAfter != null && after != null && saleItem != null
            && !saleItem.getType().isAir() && quantity > 0
            && removeSimilar(expectedAfter, saleItem, quantity) == quantity
            && sameContents(expectedAfter, after);
    }

    private static boolean isExactBuyContentsDelta(
        ItemStack[] before,
        ItemStack[] after,
        ItemStack buyItem,
        int quantity
    ) {
        if (before == null || after == null || buyItem == null
            || buyItem.getType().isAir() || quantity <= 0) {
            return false;
        }
        InventoryInsertionPlan expected = planInsertion(
            before,
            buyItem,
            quantity
        );
        return expected.added() == quantity
            && sameContents(expected.after(), after);
    }

    private static boolean sameContents(ItemStack[] left, ItemStack[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        for (int slot = 0; slot < left.length; slot++) {
            if (!Objects.equals(left[slot], right[slot])) {
                return false;
            }
        }
        return true;
    }

    private static String requireString(
        ConfigurationSection section,
        String path
    ) {
        return WarehouseJournalValidation.requireNonBlank(
            section.get(path),
            path
        );
    }

    private static String optionalString(
        ConfigurationSection section,
        String path
    ) {
        Object value = section.get(path);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return stringValue;
    }

    private static int requireInt(
        ConfigurationSection section,
        String path
    ) {
        return WarehouseJournalValidation.requireInt(section.get(path), path);
    }

    private static long requireLong(
        ConfigurationSection section,
        String path
    ) {
        return WarehouseJournalValidation.requireLong(section.get(path), path);
    }

    private static boolean requireBoolean(
        ConfigurationSection section,
        String path
    ) {
        Object value = section.get(path);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return booleanValue;
    }

    private static List<?> requireList(
        ConfigurationSection section,
        String path
    ) {
        Object value = section.get(path);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        return list;
    }

    private static List<?> optionalList(
        ConfigurationSection section,
        String path
    ) {
        if (!section.contains(path)) {
            return List.of();
        }
        return requireList(section, path);
    }

    private static List<String> requireStringList(
        ConfigurationSection section,
        String path
    ) {
        List<String> result = new ArrayList<String>();
        for (Object value : requireList(section, path)) {
            if (!(value instanceof String stringValue)) {
                throw new IllegalArgumentException(
                    path + " must contain only strings"
                );
            }
            result.add(stringValue);
        }
        return result;
    }

    private void load() {
        this.records.clear();
        this.buyDeliveries.clear();
        this.pendingSaleTransaction = null;
        this.pendingBuyTransfer = null;
        if (!this.storageFile.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(this.storageFile);
        int storageVersion = config.getInt("version", -1);
        if (storageVersion < 1 || storageVersion > 3) {
            throw new IllegalStateException(
                "Unsupported or unreadable physical warehouse data version: "
                    + storageVersion
            );
        }
        boolean loadFailed = false;
        ConfigurationSection warehouses = null;
        if (config.contains("warehouses")) {
            if (config.isConfigurationSection("warehouses")) {
                warehouses = config.getConfigurationSection("warehouses");
            } else {
                loadFailed = true;
                this.plugin.getLogger().severe(
                    "Physical warehouse 'warehouses' node must be a section."
                );
            }
        }
        if (warehouses != null) {
            for (String id : warehouses.getKeys(false)) {
                ConfigurationSection section = warehouses.getConfigurationSection(id);
                if (section == null) {
                    loadFailed = true;
                    this.plugin.getLogger().severe(
                        "Physical warehouse record " + id
                            + " must be a configuration section."
                    );
                    continue;
                }
                try {
                    WarehouseRecord record = this.loadWarehouseRecord(id, section);
                    if (this.records.put(id, record) != null) {
                        throw new IllegalArgumentException(
                            "duplicate physical warehouse id"
                        );
                    }
                } catch (RuntimeException exception) {
                    loadFailed = true;
                    this.plugin.getLogger().log(
                        Level.SEVERE,
                        "Invalid physical warehouse record " + id,
                        exception
                    );
                }
            }
        }
        List<?> rawDeliveries = List.of();
        if (config.contains("buy-deliveries")) {
            Object rawDeliveryNode = config.get("buy-deliveries");
            if (rawDeliveryNode instanceof List<?> list) {
                rawDeliveries = list;
            } else {
                loadFailed = true;
                this.plugin.getLogger().severe(
                    "Physical warehouse 'buy-deliveries' node must be a list."
                );
            }
        }
        for (Object rawDeliveryValue : rawDeliveries) {
            try {
                if (!(rawDeliveryValue instanceof Map<?, ?> rawDelivery)) {
                    throw new IllegalArgumentException(
                        "buy-deliveries contains a non-map entry"
                    );
                }
                PendingBuyDelivery delivery = this.loadBuyDelivery(rawDelivery);
                if (this.buyDeliveries.put(
                        delivery.idempotencyKey(),
                        delivery
                    ) != null) {
                    throw new IllegalArgumentException(
                        "duplicate physical warehouse buy delivery key"
                    );
                }
            } catch (RuntimeException exception) {
                loadFailed = true;
                this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Invalid physical warehouse buy delivery",
                    exception
                );
            }
        }

        ConfigurationSection journal = null;
        if (config.contains("journal")) {
            if (config.isConfigurationSection("journal")) {
                journal = config.getConfigurationSection("journal");
            } else {
                loadFailed = true;
                this.plugin.getLogger().severe(
                    "Physical warehouse 'journal' node must be a section."
                );
            }
        }
        ConfigurationSection saleJournal = null;
        ConfigurationSection buyJournal = null;
        if (journal != null && journal.contains("sale")) {
            if (journal.isConfigurationSection("sale")) {
                saleJournal = journal.getConfigurationSection("sale");
            } else {
                loadFailed = true;
                this.plugin.getLogger().severe(
                    "Physical warehouse 'journal.sale' node must be a section."
                );
            }
        }
        if (journal != null && journal.contains("buy")) {
            if (journal.isConfigurationSection("buy")) {
                buyJournal = journal.getConfigurationSection("buy");
            } else {
                loadFailed = true;
                this.plugin.getLogger().severe(
                    "Physical warehouse 'journal.buy' node must be a section."
                );
            }
        }
        if (saleJournal != null) {
            try {
                this.pendingSaleTransaction = this.loadSaleJournal(saleJournal);
            } catch (RuntimeException exception) {
                loadFailed = true;
                this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to load physical warehouse sale journal.",
                    exception
                );
            }
        }
        if (buyJournal != null) {
            try {
                this.pendingBuyTransfer = this.loadBuyJournal(buyJournal);
            } catch (RuntimeException exception) {
                loadFailed = true;
                this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to load physical warehouse buy journal.",
                    exception
                );
            }
        }
        if (!loadFailed && this.pendingSaleTransaction != null) {
            try {
                this.validateLoadedSaleJournal(this.pendingSaleTransaction);
            } catch (RuntimeException exception) {
                loadFailed = true;
                this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Physical warehouse sale journal relationship is invalid.",
                    exception
                );
            }
        }
        if (!loadFailed && this.pendingBuyTransfer != null) {
            try {
                this.validateLoadedBuyJournal(this.pendingBuyTransfer);
            } catch (RuntimeException exception) {
                loadFailed = true;
                this.plugin.getLogger().log(
                    Level.SEVERE,
                    "Physical warehouse buy journal relationship is invalid.",
                    exception
                );
            }
        }
        if (loadFailed) {
            throw new IllegalStateException(
                "Physical warehouse data is incomplete or corrupt; refusing to start"
            );
        }
    }

    private boolean save() {
        if (this.dataLoaded && !this.plugin.isStorageAvailable()) {
            return false;
        }
        if (!this.plugin.getDataFolder().exists()
            && !this.plugin.getDataFolder().mkdirs()) {
            return false;
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("version", 3);
        for (WarehouseRecord record : this.records.values()) {
            String path = "warehouses." + record.id();
            config.set(path + ".type", record.type().name());
            config.set(path + ".owner-uuid", record.ownerUuid().toString());
            config.set(path + ".owner-name", record.ownerName());
            config.set(
                path + ".price",
                record.price() == null ? null : record.price().toPlainString()
            );
            config.set(path + ".active", record.active());
            config.set(path + ".created-at", record.createdAt());
            List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
            for (BlockKey block : record.blocks()) {
                Map<String, Object> serialized = new LinkedHashMap<String, Object>();
                serialized.put("world-uuid", block.worldUuid().toString());
                serialized.put("world-name", block.worldName());
                serialized.put("x", block.x());
                serialized.put("y", block.y());
                serialized.put("z", block.z());
                serialized.put("original-name", record.originalNames().get(block));
                blocks.add(serialized);
            }
            config.set(path + ".blocks", blocks);
            List<Map<String, Object>> orders = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, Integer> entry : record.sellOrders().entrySet()) {
                Map<String, Object> serialized = new LinkedHashMap<String, Object>();
                serialized.put("item-base64", entry.getKey());
                serialized.put("order-id", entry.getValue());
                orders.add(serialized);
            }
            config.set(path + ".sell-orders", orders);
        }
        List<Map<String, Object>> deliveries =
            new ArrayList<Map<String, Object>>();
        for (PendingBuyDelivery delivery : this.buyDeliveries.values()) {
            Map<String, Object> serialized = new LinkedHashMap<String, Object>();
            serialized.put("key", delivery.idempotencyKey());
            serialized.put("owner-uuid", delivery.ownerUuid().toString());
            serialized.put("item-base64", delivery.itemBase64());
            serialized.put("original-quantity", delivery.originalQuantity());
            serialized.put("remaining-quantity", delivery.remainingQuantity());
            serialized.put("created-at", delivery.createdAt());
            deliveries.add(serialized);
        }
        config.set("buy-deliveries", deliveries);
        if (this.pendingSaleTransaction != null) {
            PendingSaleTransaction transaction = this.pendingSaleTransaction;
            config.set("journal.sale.id", transaction.id());
            config.set("journal.sale.warehouse-id", transaction.warehouseId());
            config.set("journal.sale.order-id", transaction.orderId());
            config.set("journal.sale.buy-order-id", transaction.buyOrderId());
            config.set(
                "journal.sale.expected-filled-quantity",
                transaction.expectedFilledQuantity()
            );
            config.set(
                "journal.sale.expected-buy-filled-quantity",
                transaction.expectedBuyFilledQuantity()
            );
            config.set("journal.sale.item-base64", transaction.itemBase64());
            config.set("journal.sale.quantity", transaction.quantity());
            config.set("journal.sale.before", transaction.before());
            config.set("journal.sale.after", transaction.after());
            config.set("journal.sale.decision", transaction.decision().name());
            config.set("journal.sale.reason", transaction.reason());
        }
        if (this.pendingBuyTransfer != null) {
            PendingBuyTransfer transfer = this.pendingBuyTransfer;
            config.set("journal.buy.id", transfer.id());
            config.set("journal.buy.delivery-key", transfer.deliveryKey());
            config.set("journal.buy.warehouse-id", transfer.warehouseId());
            config.set("journal.buy.quantity", transfer.quantity());
            config.set("journal.buy.before", transfer.before());
            config.set("journal.buy.after", transfer.after());
        }
        File temporary = new File(
            this.storageFile.getParentFile(),
            this.storageFile.getName() + ".tmp-" + System.nanoTime()
        );
        try {
            config.save(temporary);
            DurableFiles.replace(
                temporary.toPath(),
                this.storageFile.toPath()
            );
            return true;
        } catch (DurableFiles.ReplaceException exception) {
            if (exception.isTargetStateUncertain()) {
                String reason =
                    "uncertain durable replacement for physical warehouse state";
                this.plugin.markStorageUnavailable(reason, exception);
                throw new IllegalStateException(reason, exception);
            }
            this.plugin.getLogger().log(
                Level.SEVERE,
                "Failed to save auto warehouse data.",
                exception
            );
            return false;
        } catch (IOException exception) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "Failed to save auto warehouse data.",
                exception
            );
            return false;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private void recoverPendingTransactions() {
        if (!this.dataLoaded || !this.plugin.isStorageAvailable()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this.plugin, this::recoverPendingTransactions);
            return;
        }
        if (this.pendingSaleTransaction != null) {
            this.recoverPendingSaleTransaction();
        }
        if (this.pendingBuyTransfer != null) {
            this.recoverPendingBuyTransfer();
        }
    }

    private boolean recoverPendingSaleTransaction() {
        PendingSaleTransaction transaction = this.pendingSaleTransaction;
        if (transaction == null) {
            return true;
        }
        WarehouseRecord record = this.records.get(transaction.warehouseId());
        if (record == null) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_SALE_RECOVERY_BLOCKED transaction="
                    + transaction.id() + " reason=warehouse_missing"
            );
            return false;
        }
        try {
            this.validateLoadedSaleJournal(transaction);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] WAREHOUSE_SALE_RECOVERY_BLOCKED transaction="
                    + transaction.id() + " reason=journal_revalidation_failed",
                exception
            );
            this.quarantineRecord(
                record,
                "sale journal revalidation failed"
            );
            return false;
        }
        this.lockedWarehouseIds.add(record.id());
        ChestTarget target = this.resolveRawRecordTarget(record, true);
        ItemStack[] before = deserializeContents(
            transaction.before(),
            target == null ? -1 : target.inventory().getSize()
        );
        ItemStack[] after = deserializeContents(
            transaction.after(),
            target == null ? -1 : target.inventory().getSize()
        );
        if (target == null || before == null || after == null) {
            this.quarantineRecord(
                record,
                "sale recovery chest/snapshot unavailable"
            );
            return false;
        }
        List<String> current = serializeContents(target.inventory().getContents());
        if (current == null) {
            this.quarantineRecord(record, "sale recovery current snapshot failed");
            return false;
        }
        WarehouseSaleRecoveryPolicy.Snapshot snapshot =
            current.equals(transaction.before())
                ? WarehouseSaleRecoveryPolicy.Snapshot.BEFORE
                : current.equals(transaction.after())
                    ? WarehouseSaleRecoveryPolicy.Snapshot.AFTER
                    : WarehouseSaleRecoveryPolicy.Snapshot.CONFLICT;
        Order sellOrder = this.plugin.getStorageManager().getOrder(
            transaction.orderId()
        );
        Order buyOrder = transaction.buyOrderId() <= 0
            ? null
            : this.plugin.getStorageManager().getOrder(transaction.buyOrderId());
        boolean sellOrderAdvanced = sellOrder != null
            && transaction.warehouseId().equals(sellOrder.getSourceWarehouseId())
            && sellOrder.getFilledQty() >= transaction.expectedFilledQuantity();
        boolean buyOrderAdvanced = buyOrder != null
            && buyOrder.getFilledQty() >= transaction.expectedBuyFilledQuantity();
        WarehouseSaleRecoveryPolicy.Action action =
            WarehouseSaleRecoveryPolicy.decide(
                transaction.decision(),
                snapshot,
                buyOrderAdvanced,
                sellOrderAdvanced
            );
        if (action == WarehouseSaleRecoveryPolicy.Action.QUARANTINE) {
            this.quarantineRecord(
                record,
                "sale recovery decision=" + transaction.decision()
                    + " snapshot=" + snapshot
                    + " buy_advanced=" + buyOrderAdvanced
                    + " sell_advanced=" + sellOrderAdvanced
                    + (transaction.reason().isBlank()
                        ? ""
                        : " reason=" + transaction.reason())
            );
            return false;
        }
        List<String> desiredSnapshot = null;
        ItemStack[] desiredContents = null;
        if (action == WarehouseSaleRecoveryPolicy.Action.APPLY_BEFORE_AND_CLEAR) {
            desiredSnapshot = transaction.before();
            desiredContents = before;
        } else if (action
            == WarehouseSaleRecoveryPolicy.Action.APPLY_AFTER_AND_CLEAR) {
            desiredSnapshot = transaction.after();
            desiredContents = after;
        }
        if (desiredSnapshot != null && !current.equals(desiredSnapshot)) {
            try {
                target.inventory().setContents(desiredContents);
            } catch (Throwable throwable) {
                this.quarantineRecord(
                    record,
                    "sale recovery inventory write failed: "
                        + throwable.getClass().getSimpleName()
                );
                return false;
            }
            List<String> verified = serializeContents(
                target.inventory().getContents()
            );
            if (!desiredSnapshot.equals(verified)) {
                this.quarantineRecord(
                    record,
                    "sale recovery inventory verification failed"
                );
                return false;
            }
        }
        this.pendingSaleTransaction = null;
        if (!this.save()) {
            this.pendingSaleTransaction = transaction;
            return false;
        }
        this.lockedWarehouseIds.remove(record.id());
        this.scheduleSync(record.id());
        this.plugin.getLogger().warning(
            "[AssetAudit] WAREHOUSE_SALE_RECOVERED transaction="
                + transaction.id() + " action=" + action
        );
        return true;
    }

    private boolean recoverPendingBuyTransfer() {
        PendingBuyTransfer transfer = this.pendingBuyTransfer;
        if (transfer == null) {
            return true;
        }
        WarehouseRecord record = this.records.get(transfer.warehouseId());
        PendingBuyDelivery delivery =
            this.buyDeliveries.get(transfer.deliveryKey());
        if (record == null || delivery == null) {
            this.plugin.getLogger().severe(
                "[AssetAudit] BUY_WAREHOUSE_RECOVERY_BLOCKED transaction="
                    + transfer.id() + " reason=record_missing"
            );
            return false;
        }
        try {
            this.validateLoadedBuyJournal(transfer);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] BUY_WAREHOUSE_RECOVERY_BLOCKED transaction="
                    + transfer.id() + " reason=journal_revalidation_failed",
                exception
            );
            this.quarantineRecord(
                record,
                "buy journal revalidation failed"
            );
            return false;
        }
        this.lockedWarehouseIds.add(record.id());
        ChestTarget target = this.resolveRawRecordTarget(record, true);
        ItemStack[] before = deserializeContents(
            transfer.before(),
            target == null ? -1 : target.inventory().getSize()
        );
        ItemStack[] after = deserializeContents(
            transfer.after(),
            target == null ? -1 : target.inventory().getSize()
        );
        if (target == null || before == null || after == null) {
            this.quarantineRecord(
                record,
                "buy recovery chest/snapshot unavailable"
            );
            return false;
        }
        List<String> current = serializeContents(target.inventory().getContents());
        if (current == null) {
            this.quarantineRecord(record, "buy recovery current snapshot failed");
            return false;
        }
        int previousRemaining = delivery.remainingQuantity();
        boolean applied;
        if (current.equals(transfer.before())) {
            applied = false;
        } else if (current.equals(transfer.after())) {
            applied = true;
            delivery.setRemainingQuantity(
                Math.max(0, previousRemaining - transfer.quantity())
            );
        } else {
            this.quarantineRecord(
                record,
                "buy recovery chest differs from before and after"
            );
            return false;
        }
        this.pendingBuyTransfer = null;
        if (!this.save()) {
            delivery.setRemainingQuantity(previousRemaining);
            this.pendingBuyTransfer = transfer;
            return false;
        }
        this.lockedWarehouseIds.remove(record.id());
        this.plugin.getLogger().warning(
            "[AssetAudit] BUY_WAREHOUSE_RECOVERED transaction="
                + transfer.id() + " applied=" + applied
        );
        return true;
    }

    private ChestTarget resolveRawRecordTarget(
        WarehouseRecord record,
        boolean loadChunks
    ) {
        if (record == null || record.blocks().isEmpty()) {
            return null;
        }
        if (loadChunks && !this.loadAllRecordChunks(record)) {
            return null;
        }
        ChestTarget target = this.resolveTarget(record.blocks().get(0), false);
        return target != null && sameBlocks(target.blocks(), record.blocks())
            ? target
            : null;
    }

    private void quarantineRecord(WarehouseRecord record, String reason) {
        if (record != null) {
            record.setActive(false);
            this.removeActiveIndex(record);
            this.lockedWarehouseIds.add(record.id());
            if (!this.save()) {
                this.plugin.getLogger().severe(
                    "[AssetAudit] WAREHOUSE_QUARANTINE_SAVE_FAILED warehouse="
                        + record.id()
                        + " in_memory_lock_retained=true reason=" + reason
                );
            }
        }
        this.plugin.getLogger().severe(
            "[AssetAudit] WAREHOUSE_QUARANTINED warehouse="
                + (record == null ? "unknown" : record.id())
                + " reason=" + reason
        );
    }

    private boolean commitSaleReservation(String transactionId) {
        return this.persistSaleDecision(
            transactionId,
            WarehouseSaleRecoveryPolicy.Decision.COMMIT,
            ""
        );
    }

    private boolean rollbackSaleReservation(String transactionId) {
        return this.persistSaleDecision(
            transactionId,
            WarehouseSaleRecoveryPolicy.Decision.ROLLBACK,
            ""
        );
    }

    private boolean persistSaleDecision(
        String transactionId,
        WarehouseSaleRecoveryPolicy.Decision decision,
        String reason
    ) {
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_SALE_DECISION_BLOCKED transaction="
                    + transactionId + " reason=off_main_thread"
            );
            return false;
        }
        PendingSaleTransaction transaction = this.pendingSaleTransaction;
        if (transaction == null || !transaction.id().equals(transactionId)) {
            return false;
        }
        PendingSaleTransaction decided = transaction.withDecision(
            decision,
            reason
        );
        this.pendingSaleTransaction = decided;
        if (!this.save()) {
            this.pendingSaleTransaction = transaction;
            this.plugin.getLogger().severe(
                "[AssetAudit] WAREHOUSE_SALE_DECISION_SAVE_FAILED transaction="
                    + transactionId + " decision=" + decision
            );
            return false;
        }
        return this.recoverPendingSaleTransaction();
    }

    private void quarantineSaleReservation(
        String transactionId,
        String reason
    ) {
        PendingSaleTransaction transaction = this.pendingSaleTransaction;
        if (transaction == null || !transaction.id().equals(transactionId)) {
            return;
        }
        this.pendingSaleTransaction = transaction.withDecision(
            WarehouseSaleRecoveryPolicy.Decision.IN_DOUBT,
            reason
        );
        WarehouseRecord record = this.records.get(transaction.warehouseId());
        this.quarantineRecord(record, reason);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLockedInventoryClick(InventoryClickEvent event) {
        if (this.isLockedInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        this.scheduleByInventory(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLockedInventoryDrag(InventoryDragEvent event) {
        if (this.isLockedInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        this.scheduleByInventory(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        this.scheduleByInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (this.isLockedInventory(event.getSource())
            || this.isLockedInventory(event.getDestination())) {
            event.setCancelled(true);
            return;
        }
        this.scheduleByInventory(event.getSource());
        this.scheduleByInventory(event.getDestination());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (this.isLockedInventory(event.getInventory())) {
            event.setCancelled(true);
            return;
        }
        this.scheduleByInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLockedBlockBreak(BlockBreakEvent event) {
        WarehouseRecord record = this.findRecordByBlock(event.getBlock());
        if (record == null) {
            if (this.isFailClosedWarehouseBlock(event.getBlock())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                    "§c交易仓库数据正在保护中，暂时不能破坏，请联系管理员处理。"
                );
            }
            return;
        }
        if (!this.prepareWarehouseDestruction(record)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                "§c交易仓库正在结算或清理中，暂时不能破坏，请稍后重试。"
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLockedBlockDestroy(BlockDestroyEvent event) {
        WarehouseRecord record = this.findRecordByBlock(event.getBlock());
        if (record == null || this.prepareWarehouseDestruction(record)) {
            return;
        }
        this.plugin.markStorageUnavailable(
            "direct warehouse block destruction cleanup incomplete",
            null
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLockedAdjacentChestPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Chest)) {
            return;
        }
        if (this.hasFailClosedAdjacentWarehouseBlock(event.getBlockPlaced())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                "§c交易仓库数据正在保护中，暂时不能改变箱体结构。"
            );
            return;
        }
        Set<String> affected = new HashSet<String>();
        this.collectAdjacentWarehouseIds(event.getBlockPlaced(), affected);
        for (String warehouseId : affected) {
            if (this.isWarehouseLocked(warehouseId)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(
                    "§c交易仓库正在结算或恢复中，暂时不能改变箱体结构。"
                );
                return;
            }
        }
        this.scheduleStructureVerification(affected);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLockedBlockExplode(BlockExplodeEvent event) {
        this.prepareExplodedWarehouses(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLockedEntityExplode(EntityExplodeEvent event) {
        this.prepareExplodedWarehouses(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (this.findRecordByBlock(block) != null
                || this.findRecordByBlock(
                    block.getRelative(event.getDirection())
                ) != null
                || this.isFailClosedWarehouseBlock(block)
                || this.isFailClosedWarehouseBlock(
                    block.getRelative(event.getDirection())
                )) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (this.findRecordByBlock(block) != null
                || this.isFailClosedWarehouseBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (WarehouseRecord record : this.records.values()) {
            if (!record.active()) {
                continue;
            }
            for (BlockKey block : record.blocks()) {
                if (block.worldUuid().equals(chunk.getWorld().getUID())
                    && (block.x() >> 4) == chunk.getX()
                    && (block.z() >> 4) == chunk.getZ()) {
                    this.scheduleSync(record.id());
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (WarehouseRecord record :
            new ArrayList<WarehouseRecord>(this.records.values())) {
            boolean inChunk = false;
            for (BlockKey block : record.blocks()) {
                if (block.worldUuid().equals(chunk.getWorld().getUID())
                    && (block.x() >> 4) == chunk.getX()
                    && (block.z() >> 4) == chunk.getZ()) {
                    inChunk = true;
                    break;
                }
            }
            if (!inChunk) {
                continue;
            }
            if (this.isWarehouseLocked(record.id())) {
                continue;
            }
            if (record.active() && record.type() == WarehouseType.SELL
                && this.syncSellWarehouse(record, false).failedKinds() > 0) {
                this.plugin.getLogger().warning(
                    "[AssetAudit] WAREHOUSE_CHUNK_UNLOAD_WITH_PENDING_SYNC warehouse="
                        + record.id()
                );
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.pendingSellConfigurations.remove(event.getPlayer().getUniqueId());
    }

    private WarehouseRecord findActiveRecordByBlock(Block block) {
        if (block == null) {
            return null;
        }
        String id = this.activeWarehouseByBlock.get(BlockKey.of(block.getLocation()));
        WarehouseRecord record = id == null ? null : this.records.get(id);
        return record != null && record.active() ? record : null;
    }

    private WarehouseRecord findRecordByBlock(Block block) {
        WarehouseRecord active = this.findActiveRecordByBlock(block);
        if (active != null || block == null) {
            return active;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        for (WarehouseRecord record : this.records.values()) {
            if (record.blocks().contains(key)) {
                return record;
            }
        }
        return null;
    }

    private void collectAdjacentWarehouseIds(Block block, Set<String> target) {
        if (block == null) {
            return;
        }
        int[][] offsets = new int[][]{
            {0, 0},
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
        };
        for (int[] offset : offsets) {
            Block candidate = block.getRelative(offset[0], 0, offset[1]);
            WarehouseRecord record = this.findRecordByBlock(candidate);
            if (record != null) {
                target.add(record.id());
            }
        }
    }

    private boolean hasFailClosedAdjacentWarehouseBlock(Block block) {
        if (block == null) {
            return false;
        }
        int[][] offsets = new int[][]{
            {0, 0},
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
        };
        for (int[] offset : offsets) {
            if (this.isFailClosedWarehouseBlock(
                    block.getRelative(offset[0], 0, offset[1])
                )) {
                return true;
            }
        }
        return false;
    }

    private boolean isFailClosedWarehouseBlock(Block block) {
        if (this.plugin.isStorageAvailable() || block == null) {
            return false;
        }
        BlockState state = block.getState();
        if (!(state instanceof TileState tileState)) {
            return false;
        }
        return tileState.getPersistentDataContainer().has(
                this.warehouseIdKey,
                PersistentDataType.STRING
            )
            || tileState.getPersistentDataContainer().has(
                this.warehouseTypeKey,
                PersistentDataType.STRING
            );
    }

    private Set<String> collectWarehouseIds(List<Block> blocks) {
        Set<String> warehouseIds = new LinkedHashSet<String>();
        for (Block block : blocks) {
            WarehouseRecord record = this.findRecordByBlock(block);
            if (record != null) {
                warehouseIds.add(record.id());
            }
        }
        return warehouseIds;
    }

    private void prepareExplodedWarehouses(List<Block> blocks) {
        blocks.removeIf(this::isFailClosedWarehouseBlock);
        Set<String> affected = this.collectWarehouseIds(blocks);
        for (String warehouseId : affected) {
            WarehouseRecord record = this.records.get(warehouseId);
            Set<BlockKey> recordBlocks = new HashSet<BlockKey>();
            if (record != null) {
                recordBlocks.addAll(record.blocks());
            }
            if (!this.prepareWarehouseDestruction(record)) {
                blocks.removeIf(block ->
                    recordBlocks.contains(BlockKey.of(block.getLocation()))
                );
                this.plugin.getLogger().warning(
                    "[AssetAudit] WAREHOUSE_EXPLOSION_BLOCKED warehouse="
                        + warehouseId
                        + " reason=cleanup_incomplete"
                );
            }
        }
    }

    /**
     * Runs while the chest still exists, before vanilla copies its block-entity
     * components into the dropped chest item. Cancellable destruction paths
     * keep the block intact on failure; the Paper direct-destroy path instead
     * halts market storage because cancelling that event can duplicate chests.
     */
    private boolean prepareWarehouseDestruction(WarehouseRecord record) {
        if (record == null || this.isWarehouseLocked(record.id())) {
            return false;
        }
        try {
            return this.deactivateRecord(record, true);
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "[AssetAudit] WAREHOUSE_DESTRUCTION_BLOCKED warehouse="
                    + record.id(),
                throwable
            );
            return false;
        }
    }

    private void scheduleStructureVerification(Set<String> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return;
        }
        Set<String> ids = new LinkedHashSet<String>();
        for (String warehouseId : warehouseIds) {
            WarehouseRecord record = this.records.get(warehouseId);
            if (record == null || !record.active()
                || this.lockedWarehouseIds.contains(warehouseId)
                || !this.structureVerificationLocks.add(warehouseId)) {
                continue;
            }
            ids.add(warehouseId);
        }
        if (ids.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            for (String warehouseId : ids) {
                if (!this.structureVerificationLocks.remove(warehouseId)) {
                    continue;
                }
                WarehouseRecord record = this.records.get(warehouseId);
                if (record == null || !record.active()
                    || this.lockedWarehouseIds.contains(record.id())) {
                    continue;
                }
                if (this.resolveRecordTarget(record, true) == null) {
                    this.deactivateForInvalidStructure(record);
                } else if (record.type() == WarehouseType.SELL) {
                    this.syncSellWarehouse(record, true);
                } else {
                    this.applyWarehouseName(record);
                    this.routePendingBuyDeliveries(record.ownerUuid());
                }
            }
        });
    }

    private boolean isLockedInventory(Inventory inventory) {
        for (String warehouseId : this.findWarehouseIds(inventory)) {
            if (this.isWarehouseLocked(warehouseId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWarehouseLocked(String warehouseId) {
        return warehouseId != null
            && (!this.plugin.isStorageAvailable()
                || this.lockedWarehouseIds.contains(warehouseId)
                || this.structureVerificationLocks.contains(warehouseId));
    }

    private static StockSnapshot scanStock(Inventory inventory) {
        Map<String, Integer> quantities = new LinkedHashMap<String, Integer>();
        Map<String, ItemStack> representatives = new LinkedHashMap<String, ItemStack>();
        int invalidKinds = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            if (MarketGuiItem.isMarked(stack)) {
                invalidKinds++;
                continue;
            }
            ItemStack single = stack.clone();
            single.setAmount(1);
            String itemBase64 = ItemSerializer.itemToBase64(single);
            if (itemBase64 == null || itemBase64.isBlank()) {
                invalidKinds++;
                continue;
            }
            quantities.merge(itemBase64, stack.getAmount(), Integer::sum);
            representatives.putIfAbsent(itemBase64, single);
        }
        return new StockSnapshot(quantities, representatives, invalidKinds);
    }

    private static int countSimilar(Inventory inventory, String itemBase64) {
        ItemStack target = ItemSerializer.itemFromBase64(itemBase64);
        if (target == null) {
            return -1;
        }
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && stack.isSimilar(target)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static int removeSimilar(
        ItemStack[] contents,
        String itemBase64,
        int quantity
    ) {
        ItemStack target = ItemSerializer.itemFromBase64(itemBase64);
        return removeSimilar(contents, target, quantity);
    }

    private static int removeSimilar(
        ItemStack[] contents,
        ItemStack target,
        int quantity
    ) {
        if (contents == null || target == null || target.getType().isAir()
            || quantity <= 0) {
            return 0;
        }
        int remaining = quantity;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(target)) {
                continue;
            }
            int removed = Math.min(stack.getAmount(), remaining);
            int nextAmount = stack.getAmount() - removed;
            if (nextAmount <= 0) {
                contents[slot] = null;
            } else {
                ItemStack next = stack.clone();
                next.setAmount(nextAmount);
                contents[slot] = next;
            }
            remaining -= removed;
        }
        return quantity - remaining;
    }

    private static InventoryInsertionPlan planInsertion(
        ItemStack[] current,
        ItemStack baseItem,
        int quantity
    ) {
        ItemStack[] after = cloneContents(current);
        if (after == null || baseItem == null || quantity <= 0) {
            return new InventoryInsertionPlan(after, 0);
        }
        int remaining = quantity;
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            ItemStack stack = after[slot];
            if (stack == null || stack.getType().isAir()
                || !stack.isSimilar(baseItem)) {
                continue;
            }
            int capacity = Math.max(
                0,
                stack.getMaxStackSize() - stack.getAmount()
            );
            if (capacity <= 0) {
                continue;
            }
            int inserted = Math.min(capacity, remaining);
            ItemStack next = stack.clone();
            next.setAmount(stack.getAmount() + inserted);
            after[slot] = next;
            remaining -= inserted;
        }
        int maxStack = Math.max(1, baseItem.getMaxStackSize());
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            ItemStack stack = after[slot];
            if (stack != null && !stack.getType().isAir()) {
                continue;
            }
            int inserted = Math.min(maxStack, remaining);
            ItemStack next = baseItem.clone();
            next.setAmount(inserted);
            after[slot] = next;
            remaining -= inserted;
        }
        return new InventoryInsertionPlan(after, quantity - remaining);
    }

    private static ItemStack[] cloneContents(Inventory inventory) {
        return inventory == null ? null : cloneContents(inventory.getContents());
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return null;
        }
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            snapshot[index] = contents[index] == null ? null : contents[index].clone();
        }
        return snapshot;
    }

    private static List<String> serializeContents(ItemStack[] contents) {
        if (contents == null) {
            return null;
        }
        List<String> serialized = new ArrayList<String>(contents.length);
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) {
                serialized.add("");
                continue;
            }
            String base64 = ItemSerializer.itemToBase64(item);
            if (base64 == null || base64.isBlank()) {
                return null;
            }
            serialized.add(base64);
        }
        return serialized;
    }

    private static ItemStack[] deserializeContents(
        List<String> serialized,
        int expectedSize
    ) {
        if (serialized == null || expectedSize < 0
            || serialized.size() != expectedSize) {
            return null;
        }
        ItemStack[] contents = new ItemStack[expectedSize];
        for (int slot = 0; slot < expectedSize; slot++) {
            String base64 = serialized.get(slot);
            if (base64 == null || base64.isBlank()) {
                continue;
            }
            ItemStack item = ItemSerializer.itemFromBase64(base64);
            if (item == null || item.getType().isAir()) {
                return null;
            }
            contents[slot] = item;
        }
        return contents;
    }

    private static boolean sameBlocks(List<BlockKey> left, List<BlockKey> right) {
        return new HashSet<BlockKey>(left).equals(new HashSet<BlockKey>(right));
    }

    private static String formatPrice(BigDecimal price) {
        return price == null ? "0" : price.stripTrailingZeros().toPlainString();
    }

    private enum WarehouseType {
        SELL,
        BUY
    }

    private static final class WarehouseRecord {
        private final String id;
        private final WarehouseType type;
        private final UUID ownerUuid;
        private final String ownerName;
        private final BigDecimal price;
        private boolean active;
        private final long createdAt;
        private final List<BlockKey> blocks;
        private final Map<BlockKey, String> originalNames;
        private final Map<String, Integer> sellOrders;

        private WarehouseRecord(
            String id,
            WarehouseType type,
            UUID ownerUuid,
            String ownerName,
            BigDecimal price,
            boolean active,
            long createdAt,
            List<BlockKey> blocks,
            Map<BlockKey, String> originalNames,
            Map<String, Integer> sellOrders
        ) {
            this.id = id;
            this.type = type;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.price = price;
            this.active = active;
            this.createdAt = createdAt;
            this.blocks = blocks;
            this.originalNames = originalNames;
            this.sellOrders = sellOrders;
        }

        private String id() {
            return this.id;
        }

        private WarehouseType type() {
            return this.type;
        }

        private UUID ownerUuid() {
            return this.ownerUuid;
        }

        private String ownerName() {
            return this.ownerName;
        }

        private BigDecimal price() {
            return this.price;
        }

        private boolean active() {
            return this.active;
        }

        private void setActive(boolean active) {
            this.active = active;
        }

        private long createdAt() {
            return this.createdAt;
        }

        private List<BlockKey> blocks() {
            return this.blocks;
        }

        private Map<BlockKey, String> originalNames() {
            return this.originalNames;
        }

        private Map<String, Integer> sellOrders() {
            return this.sellOrders;
        }
    }

    private record ChestTarget(List<BlockKey> blocks, Inventory inventory) {
    }

    private record PendingSellConfiguration(List<BlockKey> blocks, long createdAt) {
    }

    private record OrderLink(String warehouseId, String itemBase64) {
    }

    private record StockSnapshot(
        Map<String, Integer> quantities,
        Map<String, ItemStack> representatives,
        int invalidKinds
    ) {
    }

    private record SyncResult(int failedKinds) {
        private static final SyncResult EMPTY = new SyncResult(0);
    }

    private record ConfigurationTargetCheck(
        WarehouseRecord existing,
        boolean available,
        String message
    ) {
        private static ConfigurationTargetCheck existing(WarehouseRecord record) {
            return new ConfigurationTargetCheck(record, true, "");
        }

        private static ConfigurationTargetCheck availableTarget() {
            return new ConfigurationTargetCheck(null, true, "");
        }

        private static ConfigurationTargetCheck blocked(String message) {
            return new ConfigurationTargetCheck(null, false, message);
        }
    }

    private record InventoryInsertionPlan(ItemStack[] after, int added) {
    }

    private record PendingSaleTransaction(
        String id,
        String warehouseId,
        int orderId,
        int buyOrderId,
        int expectedFilledQuantity,
        int expectedBuyFilledQuantity,
        String itemBase64,
        int quantity,
        List<String> before,
        List<String> after,
        WarehouseSaleRecoveryPolicy.Decision decision,
        String reason
    ) {
        private PendingSaleTransaction {
            id = WarehouseJournalValidation.requireUuid(
                id,
                "sale journal transaction id"
            );
            warehouseId = WarehouseJournalValidation.requireUuid(
                warehouseId,
                "sale journal warehouse id"
            );
            orderId = WarehouseJournalValidation.requirePositiveInt(
                orderId,
                "sale journal order id"
            );
            buyOrderId = WarehouseJournalValidation.requirePositiveInt(
                buyOrderId,
                "sale journal buy order id"
            );
            if (orderId == buyOrderId) {
                throw new IllegalArgumentException(
                    "sale journal buy and sell order ids must differ"
                );
            }
            quantity = WarehouseJournalValidation.requirePositiveInt(
                quantity,
                "sale journal quantity"
            );
            expectedFilledQuantity =
                WarehouseJournalValidation.requireExpectedFilled(
                    expectedFilledQuantity,
                    quantity,
                    "sale journal expected sell filled quantity"
                );
            expectedBuyFilledQuantity =
                WarehouseJournalValidation.requireExpectedFilled(
                    expectedBuyFilledQuantity,
                    quantity,
                    "sale journal expected buy filled quantity"
                );
            itemBase64 = WarehouseJournalValidation.requireNonBlank(
                itemBase64,
                "sale journal item"
            );
            if (decision == null) {
                throw new IllegalArgumentException(
                    "sale journal decision is required"
                );
            }
            before = WarehouseJournalValidation.copySnapshot(
                before,
                "sale journal before snapshot"
            );
            after = WarehouseJournalValidation.copySnapshot(
                after,
                "sale journal after snapshot"
            );
            WarehouseJournalValidation.requireDistinctSnapshotPair(
                before,
                after,
                "sale journal"
            );
            reason = reason == null ? "" : reason;
        }

        private PendingSaleTransaction withDecision(
            WarehouseSaleRecoveryPolicy.Decision nextDecision,
            String nextReason
        ) {
            return new PendingSaleTransaction(
                this.id,
                this.warehouseId,
                this.orderId,
                this.buyOrderId,
                this.expectedFilledQuantity,
                this.expectedBuyFilledQuantity,
                this.itemBase64,
                this.quantity,
                this.before,
                this.after,
                nextDecision,
                nextReason == null ? "" : nextReason
            );
        }
    }

    private record PendingBuyTransfer(
        String id,
        String deliveryKey,
        String warehouseId,
        int quantity,
        List<String> before,
        List<String> after
    ) {
        private PendingBuyTransfer {
            id = WarehouseJournalValidation.requireUuid(
                id,
                "buy journal transaction id"
            );
            deliveryKey = WarehouseJournalValidation.requireNonBlank(
                deliveryKey,
                "buy journal delivery key"
            );
            warehouseId = WarehouseJournalValidation.requireUuid(
                warehouseId,
                "buy journal warehouse id"
            );
            quantity = WarehouseJournalValidation.requirePositiveInt(
                quantity,
                "buy journal quantity"
            );
            before = WarehouseJournalValidation.copySnapshot(
                before,
                "buy journal before snapshot"
            );
            after = WarehouseJournalValidation.copySnapshot(
                after,
                "buy journal after snapshot"
            );
            WarehouseJournalValidation.requireDistinctSnapshotPair(
                before,
                after,
                "buy journal"
            );
        }
    }

    private static final class PendingBuyDelivery {
        private final String idempotencyKey;
        private final UUID ownerUuid;
        private final String itemBase64;
        private final int originalQuantity;
        private int remainingQuantity;
        private final long createdAt;

        private PendingBuyDelivery(
            String idempotencyKey,
            UUID ownerUuid,
            String itemBase64,
            int originalQuantity,
            int remainingQuantity,
            long createdAt
        ) {
            this.idempotencyKey = idempotencyKey;
            this.ownerUuid = ownerUuid;
            this.itemBase64 = itemBase64;
            this.originalQuantity = originalQuantity;
            this.remainingQuantity = remainingQuantity;
            this.createdAt = createdAt;
        }

        private String idempotencyKey() {
            return this.idempotencyKey;
        }

        private UUID ownerUuid() {
            return this.ownerUuid;
        }

        private String itemBase64() {
            return this.itemBase64;
        }

        private int originalQuantity() {
            return this.originalQuantity;
        }

        private int remainingQuantity() {
            return this.remainingQuantity;
        }

        private void setRemainingQuantity(int remainingQuantity) {
            this.remainingQuantity = remainingQuantity;
        }

        private long createdAt() {
            return this.createdAt;
        }
    }

    public static final class SaleReservation {
        private final ReservationState state;
        private final AutoWarehouseManager manager;
        private final String transactionId;
        private boolean completed;

        private SaleReservation(
            ReservationState state,
            AutoWarehouseManager manager,
            String transactionId
        ) {
            this.state = state;
            this.manager = manager;
            this.transactionId = transactionId;
        }

        public static SaleReservation notWarehouse() {
            return new SaleReservation(ReservationState.NOT_WAREHOUSE, null, null);
        }

        public static SaleReservation blocked() {
            return new SaleReservation(ReservationState.BLOCKED, null, null);
        }

        private static SaleReservation reserved(
            AutoWarehouseManager manager,
            String transactionId
        ) {
            return new SaleReservation(
                ReservationState.RESERVED,
                manager,
                transactionId
            );
        }

        public boolean allowed() {
            return this.state != ReservationState.BLOCKED;
        }

        public boolean warehouseBacked() {
            return this.state == ReservationState.RESERVED;
        }

        public boolean commit() {
            boolean success = true;
            if (!this.completed && this.state == ReservationState.RESERVED
                && this.manager != null && this.transactionId != null) {
                success = this.manager.commitSaleReservation(this.transactionId);
            }
            this.completed = true;
            return success;
        }

        public boolean rollback() {
            boolean success = true;
            if (!this.completed && this.state == ReservationState.RESERVED
                && this.manager != null && this.transactionId != null) {
                success = this.manager.rollbackSaleReservation(this.transactionId);
            }
            this.completed = true;
            return success;
        }

        public void quarantine(String reason) {
            if (!this.completed && this.state == ReservationState.RESERVED
                && this.manager != null && this.transactionId != null) {
                this.manager.quarantineSaleReservation(
                    this.transactionId,
                    reason == null || reason.isBlank()
                        ? "warehouse settlement rollback was not fully verified"
                        : reason
                );
            }
            this.completed = true;
        }
    }

    private enum ReservationState {
        NOT_WAREHOUSE,
        RESERVED,
        BLOCKED
    }

    private record BlockKey(
        UUID worldUuid,
        String worldName,
        int x,
        int y,
        int z
    ) {
        private static final Comparator<BlockKey> ORDER = Comparator
            .comparing((BlockKey key) -> key.worldUuid().toString())
            .thenComparingInt(BlockKey::x)
            .thenComparingInt(BlockKey::y)
            .thenComparingInt(BlockKey::z);

        private static BlockKey of(Location location) {
            return new BlockKey(
                location.getWorld().getUID(),
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
            );
        }

        private static BlockKey fromMap(Map<?, ?> raw) {
            try {
                Object worldUuidValue = raw.get("world-uuid");
                Object worldNameValue = raw.get("world-name");
                Object xValue = raw.get("x");
                Object yValue = raw.get("y");
                Object zValue = raw.get("z");
                if (!(worldUuidValue instanceof String worldUuidText)
                    || !(worldNameValue instanceof String worldName)
                    || worldName.isBlank()) {
                    return null;
                }
                return new BlockKey(
                    UUID.fromString(
                        WarehouseJournalValidation.requireUuid(
                            worldUuidText,
                            "warehouse block world uuid"
                        )
                    ),
                    worldName,
                    WarehouseJournalValidation.requireInt(
                        xValue,
                        "warehouse block x"
                    ),
                    WarehouseJournalValidation.requireInt(
                        yValue,
                        "warehouse block y"
                    ),
                    WarehouseJournalValidation.requireInt(
                        zValue,
                        "warehouse block z"
                    )
                );
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private World resolveWorld() {
            World world = Bukkit.getWorld(this.worldUuid);
            return world != null ? world : Bukkit.getWorld(this.worldName);
        }

        private Location resolveLocation(boolean loadChunk) {
            World world = this.resolveWorld();
            if (world == null) {
                return null;
            }
            if (loadChunk) {
                world.getChunkAt(this.x >> 4, this.z >> 4);
            } else if (!world.isChunkLoaded(this.x >> 4, this.z >> 4)) {
                return null;
            }
            return new Location(world, this.x, this.y, this.z);
        }

        private Block resolveBlock(boolean loadChunk) {
            Location location = this.resolveLocation(loadChunk);
            return location == null ? null : location.getBlock();
        }
    }
}

final class WarehouseJournalValidation {
    private WarehouseJournalValidation() {
    }

    static String requireUuid(String value, String field) {
        String checked = requireNonBlank(value, field);
        UUID parsed;
        try {
            parsed = UUID.fromString(checked);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
        if (!parsed.toString().equalsIgnoreCase(checked)) {
            throw new IllegalArgumentException(
                field + " must use canonical UUID syntax"
            );
        }
        return checked;
    }

    static String requireNonBlank(Object value, String field) {
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(
                field + " must be a non-blank string"
            );
        }
        return stringValue;
    }

    static int requirePositiveInt(Object value, String field) {
        int checked = requireInt(value, field);
        if (checked <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }

    static int requireNonNegativeInt(Object value, String field) {
        int checked = requireInt(value, field);
        if (checked < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return checked;
    }

    static int requireExpectedFilled(
        int expectedFilled,
        int quantity,
        String field
    ) {
        int checked = requirePositiveInt(expectedFilled, field);
        if (checked < quantity) {
            throw new IllegalArgumentException(
                field + " must be at least the transaction quantity"
            );
        }
        return checked;
    }

    static int requireInt(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long longValue = number.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE
            || Double.compare(number.doubleValue(), (double)longValue) != 0) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return (int)longValue;
    }

    static long requireLong(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long longValue = number.longValue();
        if (Double.compare(number.doubleValue(), (double)longValue) != 0) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return longValue;
    }

    static long requireNonNegativeLong(Object value, String field) {
        long checked = requireLong(value, field);
        if (checked < 0L) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return checked;
    }

    static boolean isRecoveryStateCompatible(
        WarehouseSaleRecoveryPolicy.Decision decision,
        int sellFilled,
        int expectedSellFilled,
        int buyFilled,
        int expectedBuyFilled,
        int quantity
    ) {
        if (decision == null || quantity <= 0
            || expectedSellFilled < quantity
            || expectedBuyFilled < quantity) {
            return false;
        }
        int sellBefore = expectedSellFilled - quantity;
        int buyBefore = expectedBuyFilled - quantity;
        boolean sellAtBefore = sellFilled == sellBefore;
        boolean sellAtAfter = sellFilled == expectedSellFilled;
        boolean buyAtBefore = buyFilled == buyBefore;
        boolean buyAtAfter = buyFilled == expectedBuyFilled;
        if (!(sellAtBefore || sellAtAfter)
            || !(buyAtBefore || buyAtAfter)) {
            return false;
        }
        return switch (decision) {
            case COMMIT -> sellAtAfter && buyAtAfter;
            case ROLLBACK -> sellAtBefore && buyAtBefore;
            case PREPARED, IN_DOUBT -> true;
        };
    }

    static List<String> copySnapshot(List<String> snapshot, String field) {
        if (snapshot == null
            || (snapshot.size() != 27 && snapshot.size() != 54)) {
            throw new IllegalArgumentException(
                field + " must contain exactly 27 or 54 slots"
            );
        }
        ArrayList<String> copy = new ArrayList<String>(snapshot.size());
        for (String entry : snapshot) {
            if (entry == null) {
                throw new IllegalArgumentException(
                    field + " cannot contain null entries"
                );
            }
            copy.add(entry);
        }
        return List.copyOf(copy);
    }

    static void requireDistinctSnapshotPair(
        List<String> before,
        List<String> after,
        String field
    ) {
        if (before == null || after == null || before.size() != after.size()) {
            throw new IllegalArgumentException(
                field + " snapshots must have the same size"
            );
        }
        if (before.equals(after)) {
            throw new IllegalArgumentException(
                field + " before and after snapshots must differ"
            );
        }
    }
}
