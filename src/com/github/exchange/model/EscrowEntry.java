/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.math.BigDecimal;

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

