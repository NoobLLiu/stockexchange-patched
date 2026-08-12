package com.github.exchange.util;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MarketPageFilter {
    public static final long SELL_CATALOG_VISIBILITY_MILLIS = TimeUnit.HOURS.toMillis(3L);

    private MarketPageFilter() {
    }

    public static boolean hasActiveBuyOrder(List<Order> orders) {
        if (orders == null) {
            return false;
        }
        for (Order order : orders) {
            if (order != null
                && order.getOrderType() == Order.OrderType.BUY
                && order.isActiveForCalculation()
                && order.getRemainingQty() > 0) {
                return true;
            }
        }
        return false;
    }

    public static long activeRemainingQuantity(List<Order> orders) {
        if (orders == null) {
            return 0L;
        }
        long total = 0L;
        for (Order order : orders) {
            if (order == null || !order.isActiveForCalculation()) {
                continue;
            }
            total += Math.max(0, order.getRemainingQty());
        }
        return total;
    }

    /**
     * Hidden ranking value for a sell catalog entry: the sum of every active
     * SELL order's remaining quantity multiplied by its unit price.
     */
    public static BigDecimal activeSellMarketValue(List<Order> orders) {
        BigDecimal total = BigDecimal.ZERO;
        if (orders == null) {
            return total;
        }
        for (Order order : orders) {
            if (order == null
                || order.getOrderType() != Order.OrderType.SELL
                || !order.isActiveForCalculation()) {
                continue;
            }
            int remainingQuantity = order.getRemainingQty();
            if (remainingQuantity <= 0) {
                continue;
            }
            total = total.add(order.getPrice().multiply(BigDecimal.valueOf(remainingQuantity)));
        }
        return total;
    }

    /**
     * Sorts every sell catalog entry by its hidden active market value without
     * exposing that value to players. Aggregate categories intentionally use
     * the exact same ranking as ordinary catalog entries.
     */
    public static int compareSellCatalogEntries(
        BigDecimal leftMarketValue,
        long leftLatestOrderCreatedAt,
        int leftItemId,
        BigDecimal rightMarketValue,
        long rightLatestOrderCreatedAt,
        int rightItemId
    ) {
        int marketValueComparison = safeMarketValue(rightMarketValue).compareTo(safeMarketValue(leftMarketValue));
        if (marketValueComparison != 0) {
            return marketValueComparison;
        }
        int latestOrderComparison = Long.compare(rightLatestOrderCreatedAt, leftLatestOrderCreatedAt);
        if (latestOrderComparison != 0) {
            return latestOrderComparison;
        }
        return Integer.compare(leftItemId, rightItemId);
    }

    private static BigDecimal safeMarketValue(BigDecimal marketValue) {
        return marketValue == null ? BigDecimal.ZERO : marketValue;
    }

    public static long latestVisibilityAt(long latestOrderCreatedAt, long catalogActivityAt) {
        if (latestOrderCreatedAt > 0L) {
            return latestOrderCreatedAt;
        }
        return Math.max(0L, catalogActivityAt);
    }

    /**
     * A SELL page must only receive its grace-period visibility from SELL-side
     * catalog activity. BUY-side registration and search intentionally have no
     * effect once the grace period has expired.
     */
    public static boolean isVisibleOnSellPage(
        long activeSellQuantity,
        long latestSellOrderCreatedAt,
        long sellCatalogActivityAt,
        long now,
        long gracePeriodMillis
    ) {
        if (activeSellQuantity > 0L) {
            return true;
        }
        long visibleAt = latestVisibilityAt(
            latestSellOrderCreatedAt,
            sellCatalogActivityAt
        );
        return visibleAt > 0L && isVisibleAfterEmpty(
            0L,
            visibleAt,
            now,
            gracePeriodMillis
        );
    }

    public static boolean isVisibleAfterEmpty(
        long activeQuantity,
        long latestOrderCreatedAt,
        long now,
        long gracePeriodMillis
    ) {
        if (activeQuantity > 0L) {
            return true;
        }
        if (latestOrderCreatedAt <= 0L || gracePeriodMillis <= 0L) {
            return false;
        }
        long age = now - latestOrderCreatedAt;
        return age < 0L || age < gracePeriodMillis;
    }

    /**
     * Explicit catalog searches should be able to find an item even when it
     * currently has no active orders and is outside the normal activity grace
     * period. The caller still applies the actual search predicate afterwards.
     */
    public static boolean isVisibleForQuery(
        long activeQuantity,
        long latestOrderCreatedAt,
        long now,
        long gracePeriodMillis,
        boolean explicitSearch
    ) {
        return explicitSearch || isVisibleAfterEmpty(
            activeQuantity,
            latestOrderCreatedAt,
            now,
            gracePeriodMillis
        );
    }
}
