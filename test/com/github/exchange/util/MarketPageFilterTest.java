package com.github.exchange.util;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;

public final class MarketPageFilterTest {
    public static void main(String[] args) {
        Order sell = order(Order.OrderType.SELL, 10, 0, Order.OrderStatus.OPEN);
        Order closedBuy = order(Order.OrderType.BUY, 10, 10, Order.OrderStatus.CLOSED);
        Order activeBuy = order(Order.OrderType.BUY, 10, 2, Order.OrderStatus.PARTIAL);

        assert !MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, closedBuy));
        assert MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, activeBuy));
        assert MarketPageFilter.activeRemainingQuantity(Arrays.asList(activeBuy)) == 8L;
        assert MarketPageFilter.latestVisibilityAt(0L, 950L) == 950L;
        assert MarketPageFilter.latestVisibilityAt(1000L, 950L) == 1000L;
        assert !MarketPageFilter.isVisibleOnSellPage(0L, 0L, 0L, 1000L, 100L, false);
        assert !MarketPageFilter.isVisibleOnSellPage(0L, 0L, 0L, 1000L, 100L, true);
        assert MarketPageFilter.isVisibleOnSellPage(0L, 0L, 950L, 1000L, 100L, false);
        assert MarketPageFilter.isVisibleOnSellPage(8L, 0L, 0L, 1000L, 100L, false);
        assert MarketPageFilter.isVisibleAfterEmpty(8L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleAfterEmpty(0L, 950L, 1000L, 100L);
        assert !MarketPageFilter.isVisibleAfterEmpty(0L, 900L, 1000L, 100L);
        assert !MarketPageFilter.isVisibleAfterEmpty(0L, 0L, 1000L, 100L);
        assert MarketPageFilter.isVisibleForQuery(0L, 0L, 1000L, 100L, true);
        assert !MarketPageFilter.isVisibleForQuery(0L, 0L, 1000L, 100L, false);
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
