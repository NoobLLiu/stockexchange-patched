/*
 * Decompiled with CFR 0.152.
 */
package com.github.exchange.manager;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.model.Trade;
import java.util.List;

public class TradeManager {
    private final StockExchangePlugin plugin;

    public TradeManager(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public List<Trade> getPlayerTrades(String playerUuid, int page) {
        int limit = 20;
        int safePage = Math.max(1, page);
        long rawOffset = (long)(safePage - 1) * limit;
        int offset = rawOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)rawOffset;
        return this.plugin.getStorageManager().getTradesByPlayer(playerUuid, limit, offset);
    }

    public List<Trade> getItemTrades(int itemId, int limit) {
        return this.plugin.getStorageManager().getTradesByItem(itemId, Math.max(0, limit));
    }

    public Trade getLastTrade(int itemId) {
        return this.plugin.getStorageManager().getLastTrade(itemId);
    }
}

