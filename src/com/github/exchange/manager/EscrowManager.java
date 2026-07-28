/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.manager;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.EscrowEntry;

public class EscrowManager {
    private final StockExchangePlugin plugin;

    public EscrowManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public void insertEscrow(EscrowEntry entry) {
        this.plugin.getStorageManager().insertEscrow(entry);
    }

    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return this.plugin.getStorageManager().getEscrow(orderId, assetType);
    }

    public void deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        this.plugin.getStorageManager().deleteEscrow(orderId, assetType);
    }
}

