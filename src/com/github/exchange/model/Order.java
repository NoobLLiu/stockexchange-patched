/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class Order {
    private int id;
    private OrderType orderType;
    private int itemId;
    private String playerUuid;
    private String playerName;
    private BigDecimal price;
    private int quantity;
    private int filledQty;
    private OrderStatus status;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Order() {
    }

    public Order(int id, OrderType orderType, int itemId, String playerUuid, BigDecimal price, int quantity, int filledQty, OrderStatus status, Timestamp createdAt, Timestamp updatedAt) {
        this.id = id;
        this.orderType = orderType;
        this.itemId = itemId;
        this.playerUuid = playerUuid;
        this.price = price;
        this.quantity = quantity;
        this.filledQty = filledQty;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getRemainingQty() {
        return this.quantity - this.filledQty;
    }

    public boolean isActive() {
        return this.status == OrderStatus.OPEN || this.status == OrderStatus.PARTIAL;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public OrderType getOrderType() {
        return this.orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public int getItemId() {
        return this.itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public String getPlayerUuid() {
        return this.playerUuid;
    }

    public void setPlayerUuid(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public BigDecimal getPrice() {
        return this.price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getQuantity() {
        return this.quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getFilledQty() {
        return this.filledQty;
    }

    public void setFilledQty(int filledQty) {
        this.filledQty = filledQty;
    }

    public OrderStatus getStatus() {
        return this.status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Timestamp getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public static enum OrderStatus {
        OPEN,
        PARTIAL,
        CLOSED,
        CANCELLED;

    }

    public static enum OrderType {
        BUY,
        SELL;

    }
}

