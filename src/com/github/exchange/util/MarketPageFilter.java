package com.github.exchange.util;

import com.github.exchange.model.Order;
import java.util.List;

public final class MarketPageFilter {
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

    public static long latestVisibilityAt(long latestOrderCreatedAt, long catalogActivityAt) {
        if (latestOrderCreatedAt > 0L) {
            return latestOrderCreatedAt;
        }
        return Math.max(0L, catalogActivityAt);
    }

    /**
     * A SELL page must only receive its grace-period visibility from SELL-side
     * catalog activity. BUY-side registration intentionally has no effect.
     */
    public static boolean isVisibleOnSellPage(
        long activeSellQuantity,
        long latestSellOrderCreatedAt,
        long sellCatalogActivityAt,
        long now,
        long gracePeriodMillis,
        boolean explicitSearch
    ) {
        if (activeSellQuantity > 0L) {
            return true;
        }
        long visibleAt = latestVisibilityAt(
            latestSellOrderCreatedAt,
            sellCatalogActivityAt
        );
        return visibleAt > 0L && (explicitSearch || isVisibleAfterEmpty(
            0L,
            visibleAt,
            now,
            gracePeriodMillis
        ));
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
