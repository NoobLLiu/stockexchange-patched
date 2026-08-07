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

    public boolean insertEscrow(EscrowEntry entry) {
        return this.plugin.getStorageManager().insertEscrow(entry);
    }

    public EscrowEntry getEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return this.plugin.getStorageManager().getEscrow(orderId, assetType);
    }

    public boolean deleteEscrow(int orderId, EscrowEntry.AssetType assetType) {
        return this.plugin.getStorageManager().deleteEscrow(orderId, assetType);
    }
}

