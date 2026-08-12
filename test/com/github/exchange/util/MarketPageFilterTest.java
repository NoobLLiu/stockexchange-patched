package com.github.exchange.util;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class MarketPageFilterTest {
    public static void main(String[] args) {
        Order sell = order(Order.OrderType.SELL, 10, 0, Order.OrderStatus.OPEN);
        Order closedBuy = order(Order.OrderType.BUY, 10, 10, Order.OrderStatus.CLOSED);
        Order activeBuy = order(Order.OrderType.BUY, 10, 2, Order.OrderStatus.PARTIAL);

        assert !MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, closedBuy));
        assert MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, activeBuy));
        assert MarketPageFilter.activeRemainingQuantity(Arrays.asList(activeBuy)) == 8L;
        Order partialSell = order(Order.OrderType.SELL, 10, 4, Order.OrderStatus.PARTIAL);
        partialSell.setPrice(new BigDecimal("2.50"));
        Order activeSell = order(Order.OrderType.SELL, 3, 0, Order.OrderStatus.OPEN);
        activeSell.setPrice(new BigDecimal("5.00"));
        Order ignoredBuy = order(Order.OrderType.BUY, 99, 0, Order.OrderStatus.OPEN);
        ignoredBuy.setPrice(new BigDecimal("100.00"));
        Order ignoredClosedSell = order(Order.OrderType.SELL, 99, 0, Order.OrderStatus.CLOSED);
        ignoredClosedSell.setPrice(new BigDecimal("100.00"));
        assert MarketPageFilter.activeSellMarketValue(
            Arrays.asList(partialSell, activeSell, ignoredBuy, ignoredClosedSell)
        ).compareTo(new BigDecimal("30.00")) == 0
            : "sell market value must sum only active SELL remaining quantity times unit price";
        assert MarketPageFilter.compareSellCatalogEntries(
            null, new BigDecimal("30.00"), 100L, 1,
            null, new BigDecimal("29.99"), 999L, 2
        ) < 0 : "ordinary sell entries must sort by hidden market value descending";
        assert MarketPageFilter.compareSellCatalogEntries(
            SpecialCategory.MUSIC_DISC, BigDecimal.ZERO, 0L, 10,
            null, new BigDecimal("99999.99"), 999L, 1
        ) < 0 : "special categories must remain ahead of ordinary sell entries";
        assert MarketPageFilter.compareSellCatalogEntries(
            null, new BigDecimal("30.00"), 100L, 1,
            null, new BigDecimal("30.00"), 99L, 2
        ) < 0 : "equal market values must keep the newer sell entry first";
        assert MarketPageFilter.latestVisibilityAt(0L, 950L) == 950L;
        assert MarketPageFilter.latestVisibilityAt(1000L, 950L) == 1000L;
        assert !MarketPageFilter.isVisibleOnSellPage(0L, 0L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleOnSellPage(0L, 0L, 950L, 1000L, 100L);
        assert MarketPageFilter.isVisibleOnSellPage(8L, 0L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleAfterEmpty(8L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleAfterEmpty(0L, 950L, 1000L, 100L);
        assert !MarketPageFilter.isVisibleAfterEmpty(0L, 900L, 1000L, 100L);
        assert !MarketPageFilter.isVisibleAfterEmpty(0L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleForQuery(0L, 0L, 1000L, 100L, true);
        assert !MarketPageFilter.isVisibleForQuery(0L, 0L, 1000L, 100L, false);
        long threeHours = TimeUnit.HOURS.toMillis(3L);
        assert MarketPageFilter.SELL_CATALOG_VISIBILITY_MILLIS == threeHours;
        assert MarketPageFilter.isVisibleOnSellPage(0L, 0L, 1L, threeHours, threeHours)
            : "a sell catalog entry must remain visible before the three-hour limit";
        assert !MarketPageFilter.isVisibleOnSellPage(0L, 0L, 1L, threeHours + 1L, threeHours)
            : "a sell catalog entry must hide after three hours without active SELL orders";
    }

    private static Order order(
        Order.OrderType type,
        int quantity,
        int filledQty,
        Order.OrderStatus status
    ) {
        Order order = new Order();
        order.setOrderType(type);
        order.setQuantity(quantity);
        order.setFilledQty(filledQty);
        order.setStatus(status);
        order.setPrice(BigDecimal.ONE);
        order.setCreatedAt(new Timestamp(1L));
        return order;
    }
}
