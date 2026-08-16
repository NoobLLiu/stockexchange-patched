package com.github.exchange.notify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates player-initiated market operations without parsing rendered chat text.
 */
public final class OperationNoticeAggregator {
    private static final class TradeEntry {
        private final String itemName;
        private int quantity;
        private BigDecimal gross = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;
        private BigDecimal net = BigDecimal.ZERO;

        private TradeEntry(String itemName) {
            this.itemName = itemName;
        }
    }

    private final Map<String, TradeEntry> purchases = new LinkedHashMap<String, TradeEntry>();
    private final Map<String, TradeEntry> sales = new LinkedHashMap<String, TradeEntry>();
    private final Map<String, Integer> listings = new LinkedHashMap<String, Integer>();
    private final List<String> legacy = new ArrayList<String>();

    public void addPurchase(
        String itemName,
        int quantity,
        BigDecimal gross,
        BigDecimal tax,
        BigDecimal charged
    ) {
        addTrade(this.purchases, itemName, quantity, gross, tax, charged);
    }

    public void addSale(
        String itemName,
        int quantity,
        BigDecimal gross,
        BigDecimal tax,
        BigDecimal received
    ) {
        addTrade(this.sales, itemName, quantity, gross, tax, received);
    }

    public void addListing(String itemName, int quantity) {
        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        this.listings.merge(itemName, quantity, Integer::sum);
    }

    public void addLegacy(String message) {
        if (message != null && !message.isBlank()) {
            this.legacy.add(message);
        }
    }

    public boolean isEmpty() {
        return this.purchases.isEmpty()
            && this.sales.isEmpty()
            && this.listings.isEmpty()
            && this.legacy.isEmpty();
    }

    public List<String> buildLines(String currencyName) {
        String currency = currencyName == null || currencyName.isBlank()
            ? "" : " " + currencyName;
        List<String> lines = new ArrayList<String>();
        for (TradeEntry entry : this.purchases.values()) {
            lines.add("§a已购买「" + entry.itemName + "」x" + entry.quantity
                + "，成交价: " + entry.gross.toPlainString()
                + "，交易税: " + entry.tax.toPlainString()
                + "，实际扣款: " + entry.net.toPlainString() + currency + "。");
        }
        for (TradeEntry entry : this.sales.values()) {
            lines.add("§a已出售「" + entry.itemName + "」x" + entry.quantity
                + "，成交价: " + entry.gross.toPlainString()
                + "，交易税: " + entry.tax.toPlainString()
                + "，实际到账: " + entry.net.toPlainString() + currency + "。");
        }
        if (!this.listings.isEmpty()) {
            StringBuilder builder = new StringBuilder("§a已上架：");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : this.listings.entrySet()) {
                if (!first) {
                    builder.append("、");
                }
                builder.append(entry.getKey()).append(" x").append(entry.getValue());
                first = false;
            }
            builder.append("。");
            lines.add(builder.toString());
        }
        lines.addAll(this.legacy);
        return lines;
    }

    private static void addTrade(
        Map<String, TradeEntry> entries,
        String itemName,
        int quantity,
        BigDecimal gross,
        BigDecimal tax,
        BigDecimal net
    ) {
        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        TradeEntry entry = entries.computeIfAbsent(itemName, TradeEntry::new);
        entry.quantity += quantity;
        entry.gross = entry.gross.add(nonNegative(gross));
        entry.tax = entry.tax.add(nonNegative(tax));
        entry.net = entry.net.add(nonNegative(net));
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0
            ? BigDecimal.ZERO : value;
    }
}
