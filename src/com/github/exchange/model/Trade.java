/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class Trade {
    private int id;
    private int itemId;
    private String buyerUuid;
    private String sellerUuid;
    private BigDecimal price;
    private int quantity;
    private BigDecimal totalAmount;
    private BigDecimal buyerFee;
    private BigDecimal sellerFee;
    private int buyOrderId;
    private int sellOrderId;
    private Timestamp tradedAt;

    public Trade() {
    }

    public Trade(int id, int itemId, String buyerUuid, String sellerUuid, BigDecimal price, int quantity, BigDecimal totalAmount, BigDecimal buyerFee, BigDecimal sellerFee, int buyOrderId, int sellOrderId, Timestamp tradedAt) {
        this.id = id;
        this.itemId = itemId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.price = price;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.buyerFee = buyerFee;
        this.sellerFee = sellerFee;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.tradedAt = tradedAt;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getItemId() {
        return this.itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public String getBuyerUuid() {
        return this.buyerUuid;
    }

    public void setBuyerUuid(String buyerUuid) {
        this.buyerUuid = buyerUuid;
    }

    public String getSellerUuid() {
        return this.sellerUuid;
    }

    public void setSellerUuid(String sellerUuid) {
        this.sellerUuid = sellerUuid;
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

    public BigDecimal getTotalAmount() {
        return this.totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getBuyerFee() {
        return this.buyerFee;
    }

    public void setBuyerFee(BigDecimal buyerFee) {
        this.buyerFee = buyerFee;
    }

    public BigDecimal getSellerFee() {
        return this.sellerFee;
    }

    public void setSellerFee(BigDecimal sellerFee) {
        this.sellerFee = sellerFee;
    }

    public int getBuyOrderId() {
        return this.buyOrderId;
    }

    public void setBuyOrderId(int buyOrderId) {
        this.buyOrderId = buyOrderId;
    }

    public int getSellOrderId() {
        return this.sellOrderId;
    }

    public void setSellOrderId(int sellOrderId) {
        this.sellOrderId = sellOrderId;
    }

    public Timestamp getTradedAt() {
        return this.tradedAt;
    }

    public void setTradedAt(Timestamp tradedAt) {
        this.tradedAt = tradedAt;
    }
}

