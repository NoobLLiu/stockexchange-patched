/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.model;

import java.sql.Timestamp;

public class ExchangeItem {
    private int id;
    private String material;
    private String nbtHash;
    private String itemBase64;
    private String displayName;
    private String itemName;
    private String itemLore;
    private String createdByUuid;
    private String createdByName;
    private Timestamp createdAt;
    private Timestamp lastStockedAt;
    private Timestamp lastEmptyAt;

    public ExchangeItem() {
    }

    public ExchangeItem(int id, String material, String nbtHash, String itemBase64, String displayName, Timestamp createdAt) {
        this.id = id;
        this.material = material;
        this.nbtHash = nbtHash;
        this.itemBase64 = itemBase64;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getMaterial() {
        return this.material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getNbtHash() {
        return this.nbtHash;
    }

    public void setNbtHash(String nbtHash) {
        this.nbtHash = nbtHash;
    }

    public String getItemBase64() {
        return this.itemBase64;
    }

    public void setItemBase64(String itemBase64) {
        this.itemBase64 = itemBase64;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getItemName() {
        return this.itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public String getItemLore() {
        return this.itemLore;
    }

    public void setItemLore(String itemLore) {
        this.itemLore = itemLore;
    }

    public Timestamp getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedByUuid() {
        return this.createdByUuid;
    }

    public void setCreatedByUuid(String createdByUuid) {
        this.createdByUuid = createdByUuid;
    }

    public String getCreatedByName() {
        return this.createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public Timestamp getLastStockedAt() {
        return this.lastStockedAt;
    }

    public void setLastStockedAt(Timestamp lastStockedAt) {
        this.lastStockedAt = lastStockedAt;
    }

    public Timestamp getLastEmptyAt() {
        return this.lastEmptyAt;
    }

    public void setLastEmptyAt(Timestamp lastEmptyAt) {
        this.lastEmptyAt = lastEmptyAt;
    }
}
