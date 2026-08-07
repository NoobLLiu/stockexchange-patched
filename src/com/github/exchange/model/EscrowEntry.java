/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.math.BigDecimal;
import java.util.UUID;

public class EscrowEntry {
    private int orderId;
    private String playerUuid;
    private AssetType assetType;
    private BigDecimal amount;
    private String itemBase64;
    private int quantity;

    public EscrowEntry() {
    }

    public EscrowEntry(int orderId, String playerUuid, AssetType assetType, BigDecimal amount, String itemBase64, int quantity) {
        this.orderId = orderId;
        this.playerUuid = playerUuid;
        this.assetType = assetType;
        this.amount = amount;
        this.itemBase64 = itemBase64;
        this.quantity = quantity;
    }

    public boolean isPersistable() {
        if (this.orderId <= 0
            || !this.isUuid(this.playerUuid)
            || this.assetType == null) {
            return false;
        }
        if (this.assetType == AssetType.MONEY) {
            return this.amount != null && this.amount.compareTo(BigDecimal.ZERO) > 0;
        }
        return this.itemBase64 != null
            && !this.itemBase64.isBlank()
            && this.quantity > 0;
    }

    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public int getOrderId() {
        return this.orderId;
    }

    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    public String getPlayerUuid() {
        return this.playerUuid;
    }

    public void setPlayerUuid(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public AssetType getAssetType() {
        return this.assetType;
    }

    public void setAssetType(AssetType assetType) {
        this.assetType = assetType;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getItemBase64() {
        return this.itemBase64;
    }

    public void setItemBase64(String itemBase64) {
        this.itemBase64 = itemBase64;
    }

    public int getQuantity() {
        return this.quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public static enum AssetType {
        MONEY,
        ITEM;

    }
}

