package com.github.exchange.util;

import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class MarketPageFilterTest {
    public static void main(String[] args) {
        long now = TimeUnit.DAYS.toMillis(100L);
        Order sell = order(Order.OrderType.SELL, 10, 0, Order.OrderStatus.OPEN);
        Order closedBuy = order(Order.OrderType.BUY, 10, 10, Order.OrderStatus.CLOSED);
        Order activeBuy = order(Order.OrderType.BUY, 10, 2, Order.OrderStatus.PARTIAL);

        assert !MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, closedBuy));
        assert MarketPageFilter.hasActiveBuyOrder(Arrays.asList(sell, activeBuy));
        assert MarketPageFilter.activeRemainingQuantity(Arrays.asList(activeBuy)) == 8L;
        Order inflatedSingleSell = sellOrder("seller-inflated", "100000.00", 1);
        MarketPageFilter.SellCatalogMetrics inflatedSingle = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(inflatedSingleSell),
            new ArrayList<Trade>(),
            now,
            1000L
        );
        assert inflatedSingle.getMonthlyTurnover().compareTo(BigDecimal.ZERO) == 0;
        assert inflatedSingle.getTrustedSupply().compareTo(BigDecimal.ZERO) == 0
            : "an item without recent real trades must not gain rank from a high listing";
        MarketPageFilter.SellCatalogMetrics staleTradeInflated = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(inflatedSingleSell),
            Arrays.asList(trade(
                "10.00",
                1,
                now - MarketPageFilter.SELL_CATALOG_TRADE_WINDOW_MILLIS - 1L
            )),
            now,
            1000L
        );
        assert staleTradeInflated.getTrustedSupply().compareTo(BigDecimal.ZERO) == 0
            : "trades older than the recent window must not legitimize a high listing";

        Order normalSell = sellOrder("seller-normal", "10.00", 10);
        MarketPageFilter.SellCatalogMetrics normal = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(normalSell),
            Arrays.asList(trade("10.00", 10, now - 1L)),
            now,
            900L
        );
        MarketPageFilter.calculateSellCatalogScores(Arrays.asList(inflatedSingle, normal));
        assert MarketPageFilter.compareSellCatalogEntries(normal, 1, inflatedSingle, 2) < 0
            : "one untraded 100000 listing must not outrank a normally traded item";

        MarketPageFilter.SellCatalogMetrics cappedSupply = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-cap", "100000.00", 1)),
            Arrays.asList(trade("10.00", 1, now - 1L)),
            now,
            800L
        );
        assert cappedSupply.getReferencePrice().compareTo(new BigDecimal("10.00")) == 0;
        assert cappedSupply.getTrustedSupply().compareTo(new BigDecimal("7.50")) == 0
            : "single-seller supply must use the three-times real-trade cap and 25 percent confidence";

        MarketPageFilter.SellCatalogMetrics oneSellerSupply = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-one", "10.00", 2)),
            Arrays.asList(trade("10.00", 1, now - 1L)),
            now,
            700L
        );
        MarketPageFilter.SellCatalogMetrics twoSellerSupply = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(
                sellOrder("seller-two-a", "10.00", 1),
                sellOrder("seller-two-b", "10.00", 1)
            ),
            Arrays.asList(trade("10.00", 1, now - 1L)),
            now,
            700L
        );
        assert twoSellerSupply.getTrustedSupply().compareTo(oneSellerSupply.getTrustedSupply()) > 0
            : "equivalent supply from more independent sellers must be more credible";

        MarketPageFilter.SellCatalogMetrics lowerPrice = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-drop", "70.00", 10)),
            Arrays.asList(trade("100.00", 10, now - 1L)),
            now,
            600L
        );
        MarketPageFilter.SellCatalogMetrics unchangedPrice = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-steady", "100.00", 10)),
            Arrays.asList(trade("100.00", 10, now - 1L)),
            now,
            600L
        );
        MarketPageFilter.calculateSellCatalogScores(Arrays.asList(lowerPrice, unchangedPrice));
        assert lowerPrice.getPriceDropRatio().compareTo(new BigDecimal("0.30")) == 0;
        assert lowerPrice.getScore() > unchangedPrice.getScore()
            : "a credible recent price reduction must improve the composite rank";

        MarketPageFilter.SellCatalogMetrics highTurnover = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-turnover-high", "10.00", 1)),
            Arrays.asList(trade("10.00", 100, now - 1L)),
            now,
            500L
        );
        MarketPageFilter.SellCatalogMetrics lowTurnover = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-turnover-low", "10.00", 1)),
            Arrays.asList(trade("10.00", 10, now - 1L)),
            now,
            500L
        );
        MarketPageFilter.calculateSellCatalogScores(Arrays.asList(highTurnover, lowTurnover));
        assert highTurnover.getScore() > lowTurnover.getScore()
            : "higher recent real turnover must improve the composite rank";

        MarketPageFilter.SellCatalogMetrics sameMetricsOlder = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-tie-a", "10.00", 1)),
            Arrays.asList(trade("10.00", 1, now - 1L)),
            now,
            400L
        );
        MarketPageFilter.SellCatalogMetrics sameMetricsNewer = MarketPageFilter.createSellCatalogMetrics(
            Arrays.asList(sellOrder("seller-tie-b", "10.00", 1)),
            Arrays.asList(trade("10.00", 1, now - 1L)),
            now,
            401L
        );
        MarketPageFilter.calculateSellCatalogScores(Arrays.asList(sameMetricsOlder, sameMetricsNewer));
        assert MarketPageFilter.compareSellCatalogEntries(
            sameMetricsNewer, 2, sameMetricsOlder, 1
        ) < 0 : "equal composite metrics must keep the newer sell entry first";

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

    private static Order sellOrder(String sellerUuid, String price, int quantity) {
        Order order = order(Order.OrderType.SELL, quantity, 0, Order.OrderStatus.OPEN);
        order.setPlayerUuid(sellerUuid);
        order.setPrice(new BigDecimal(price));
        return order;
    }

    private static Trade trade(String price, int quantity, long tradedAt) {
        Trade trade = new Trade();
        trade.setPrice(new BigDecimal(price));
        trade.setQuantity(quantity);
        trade.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));
        trade.setTradedAt(new Timestamp(tradedAt));
        return trade;
    }
}
