package com.github.exchange.util;

import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class MarketPageFilter {
    public static final long SELL_CATALOG_VISIBILITY_MILLIS = TimeUnit.HOURS.toMillis(3L);
    public static final long SELL_CATALOG_TRADE_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(30L);
    public static final int SELL_CATALOG_TRADE_SAMPLE_LIMIT = 2000;

    private static final BigDecimal SELL_PRICE_REFERENCE_MULTIPLIER = BigDecimal.valueOf(3L);
    private static final BigDecimal MAX_PRICE_DROP_RATIO = new BigDecimal("0.30");
    private static final double MONTHLY_TURNOVER_WEIGHT = 0.45D;
    private static final double TRUSTED_SUPPLY_WEIGHT = 0.35D;
    private static final double PRICE_DROP_WEIGHT = 0.20D;

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
     * Creates the hidden inputs used to rank a sell catalog entry. Only actual
     * trades in the recent window can establish a price reference, so a newly
     * listed single high-priced item cannot inflate its catalog position.
     */
    public static SellCatalogMetrics createSellCatalogMetrics(
        List<Order> activeSellOrders,
        List<Trade> trades,
        long now,
        long latestSellOrderCreatedAt
    ) {
        List<Trade> recentTrades = recentTrades(trades, now);
        BigDecimal monthlyTurnover = monthlyTurnover(recentTrades);
        BigDecimal referencePrice = weightedMedianTradePrice(recentTrades);
        TrustedSupply trustedSupply = trustedSupply(activeSellOrders, referencePrice);
        BigDecimal priceDropRatio = priceDropRatio(
            trustedSupply.lowestActiveSellPrice,
            referencePrice
        );
        return new SellCatalogMetrics(
            monthlyTurnover,
            referencePrice,
            trustedSupply.marketValue,
            priceDropRatio,
            trustedSupply.sellerCount,
            latestSellOrderCreatedAt
        );
    }

    /**
     * Calculates comparable 0..1 scores for every visible sell catalog entry.
     * Monthly turnover and trusted supply use logarithmic P90 normalization so
     * an exceptional but genuine large market does not flatten all other items.
     */
    public static void calculateSellCatalogScores(Collection<SellCatalogMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        List<BigDecimal> turnoverValues = new ArrayList<BigDecimal>();
        List<BigDecimal> supplyValues = new ArrayList<BigDecimal>();
        for (SellCatalogMetrics metric : metrics) {
            if (metric == null) {
                continue;
            }
            if (metric.monthlyTurnover.compareTo(BigDecimal.ZERO) > 0) {
                turnoverValues.add(metric.monthlyTurnover);
            }
            if (metric.trustedSupply.compareTo(BigDecimal.ZERO) > 0) {
                supplyValues.add(metric.trustedSupply);
            }
        }
        BigDecimal turnoverCeiling = percentile90(turnoverValues);
        BigDecimal supplyCeiling = percentile90(supplyValues);
        for (SellCatalogMetrics metric : metrics) {
            if (metric == null) {
                continue;
            }
            double turnoverScore = logarithmicShare(metric.monthlyTurnover, turnoverCeiling);
            double supplyScore = logarithmicShare(metric.trustedSupply, supplyCeiling);
            double priceDropScore = safe(metric.priceDropRatio)
                .divide(MAX_PRICE_DROP_RATIO, 8, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .doubleValue();
            metric.score = MONTHLY_TURNOVER_WEIGHT * turnoverScore
                + TRUSTED_SUPPLY_WEIGHT * supplyScore
                + PRICE_DROP_WEIGHT * priceDropScore;
        }
    }

    /**
     * Sorts sell entries by the composite trusted score, then by its two
     * market inputs and stable sell recency. Aggregate categories intentionally
     * use the exact same rule as ordinary catalog entries.
     */
    public static int compareSellCatalogEntries(
        SellCatalogMetrics left,
        int leftItemId,
        SellCatalogMetrics right,
        int rightItemId
    ) {
        SellCatalogMetrics safeLeft = left == null ? SellCatalogMetrics.empty() : left;
        SellCatalogMetrics safeRight = right == null ? SellCatalogMetrics.empty() : right;
        int scoreComparison = Double.compare(safeRight.score, safeLeft.score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        int turnoverComparison = safeRight.monthlyTurnover.compareTo(safeLeft.monthlyTurnover);
        if (turnoverComparison != 0) {
            return turnoverComparison;
        }
        int supplyComparison = safeRight.trustedSupply.compareTo(safeLeft.trustedSupply);
        if (supplyComparison != 0) {
            return supplyComparison;
        }
        int latestOrderComparison = Long.compare(
            safeRight.latestSellOrderCreatedAt,
            safeLeft.latestSellOrderCreatedAt
        );
        if (latestOrderComparison != 0) {
            return latestOrderComparison;
        }
        return Integer.compare(leftItemId, rightItemId);
    }

    private static List<Trade> recentTrades(List<Trade> trades, long now) {
        if (trades == null || trades.isEmpty()) {
            return Collections.emptyList();
        }
        long cutoff = now - SELL_CATALOG_TRADE_WINDOW_MILLIS;
        List<Trade> result = new ArrayList<Trade>();
        for (Trade trade : trades) {
            Timestamp tradedAt = trade == null ? null : trade.getTradedAt();
            if (tradedAt == null
                || tradedAt.getTime() < cutoff
                || trade.getPrice() == null
                || trade.getPrice().compareTo(BigDecimal.ZERO) <= 0
                || trade.getQuantity() <= 0) {
                continue;
            }
            result.add(trade);
        }
        return result;
    }

    private static BigDecimal monthlyTurnover(List<Trade> trades) {
        BigDecimal total = BigDecimal.ZERO;
        for (Trade trade : trades) {
            BigDecimal amount = trade.getTotalAmount();
            if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
                amount = trade.getPrice().multiply(BigDecimal.valueOf(trade.getQuantity()));
            }
            total = total.add(amount);
        }
        return total;
    }

    private static BigDecimal weightedMedianTradePrice(List<Trade> trades) {
        if (trades.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Trade> orderedTrades = new ArrayList<Trade>(trades);
        orderedTrades.sort(Comparator.comparing(Trade::getPrice));
        long totalQuantity = 0L;
        for (Trade trade : orderedTrades) {
            totalQuantity += Math.max(0, trade.getQuantity());
        }
        long medianQuantity = (totalQuantity + 1L) / 2L;
        long cumulativeQuantity = 0L;
        for (Trade trade : orderedTrades) {
            cumulativeQuantity += Math.max(0, trade.getQuantity());
            if (cumulativeQuantity >= medianQuantity) {
                return trade.getPrice();
            }
        }
        return orderedTrades.get(orderedTrades.size() - 1).getPrice();
    }

    private static TrustedSupply trustedSupply(
        List<Order> orders,
        BigDecimal referencePrice
    ) {
        if (orders == null
            || orders.isEmpty()
            || referencePrice == null
            || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return TrustedSupply.empty();
        }
        BigDecimal priceCap = referencePrice.multiply(SELL_PRICE_REFERENCE_MULTIPLIER);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal lowestPrice = null;
        Set<String> sellerUuids = new HashSet<String>();
        for (Order order : orders) {
            if (order == null
                || order.getOrderType() != Order.OrderType.SELL
                || !order.isActiveForCalculation()
                || order.getRemainingQty() <= 0) {
                continue;
            }
            BigDecimal cappedPrice = order.getPrice().min(priceCap);
            total = total.add(cappedPrice.multiply(BigDecimal.valueOf(order.getRemainingQty())));
            lowestPrice = lowestPrice == null ? order.getPrice() : lowestPrice.min(order.getPrice());
            String sellerUuid = order.getPlayerUuid();
            sellerUuids.add(
                sellerUuid == null || sellerUuid.isBlank()
                    ? "unknown-order-" + order.getId()
                    : sellerUuid
            );
        }
        BigDecimal sellerFactor = sellerConfidenceFactor(sellerUuids.size());
        return new TrustedSupply(
            total.multiply(sellerFactor),
            lowestPrice,
            sellerUuids.size()
        );
    }

    private static BigDecimal sellerConfidenceFactor(int sellerCount) {
        if (sellerCount <= 0) {
            return BigDecimal.ZERO;
        }
        if (sellerCount == 1) {
            return new BigDecimal("0.25");
        }
        if (sellerCount == 2) {
            return new BigDecimal("0.60");
        }
        return BigDecimal.ONE;
    }

    private static BigDecimal priceDropRatio(
        BigDecimal lowestActiveSellPrice,
        BigDecimal referencePrice
    ) {
        if (lowestActiveSellPrice == null
            || lowestActiveSellPrice.compareTo(BigDecimal.ZERO) <= 0
            || referencePrice == null
            || referencePrice.compareTo(BigDecimal.ZERO) <= 0
            || lowestActiveSellPrice.compareTo(referencePrice) >= 0) {
            return BigDecimal.ZERO;
        }
        return referencePrice.subtract(lowestActiveSellPrice)
            .divide(referencePrice, 8, RoundingMode.HALF_UP)
            .max(BigDecimal.ZERO)
            .min(MAX_PRICE_DROP_RATIO);
    }

    private static BigDecimal percentile90(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Collections.sort(values);
        int index = Math.max(0, (int)Math.ceil(values.size() * 0.90D) - 1);
        return values.get(index);
    }

    private static double logarithmicShare(BigDecimal value, BigDecimal ceiling) {
        if (value == null
            || value.compareTo(BigDecimal.ZERO) <= 0
            || ceiling == null
            || ceiling.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0D;
        }
        double numerator = Math.log1p(value.doubleValue());
        double denominator = Math.log1p(ceiling.doubleValue());
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator <= 0.0D) {
            return value.compareTo(ceiling) >= 0 ? 1.0D : 0.0D;
        }
        return Math.min(1.0D, numerator / denominator);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class TrustedSupply {
        private final BigDecimal marketValue;
        private final BigDecimal lowestActiveSellPrice;
        private final int sellerCount;

        private TrustedSupply(
            BigDecimal marketValue,
            BigDecimal lowestActiveSellPrice,
            int sellerCount
        ) {
            this.marketValue = safe(marketValue);
            this.lowestActiveSellPrice = lowestActiveSellPrice;
            this.sellerCount = sellerCount;
        }

        private static TrustedSupply empty() {
            return new TrustedSupply(BigDecimal.ZERO, null, 0);
        }
    }

    public static final class SellCatalogMetrics {
        private final BigDecimal monthlyTurnover;
        private final BigDecimal referencePrice;
        private final BigDecimal trustedSupply;
        private final BigDecimal priceDropRatio;
        private final int activeSellerCount;
        private final long latestSellOrderCreatedAt;
        private double score;

        private SellCatalogMetrics(
            BigDecimal monthlyTurnover,
            BigDecimal referencePrice,
            BigDecimal trustedSupply,
            BigDecimal priceDropRatio,
            int activeSellerCount,
            long latestSellOrderCreatedAt
        ) {
            this.monthlyTurnover = safe(monthlyTurnover);
            this.referencePrice = safe(referencePrice);
            this.trustedSupply = safe(trustedSupply);
            this.priceDropRatio = safe(priceDropRatio);
            this.activeSellerCount = Math.max(0, activeSellerCount);
            this.latestSellOrderCreatedAt = Math.max(0L, latestSellOrderCreatedAt);
        }

        private static SellCatalogMetrics empty() {
            return new SellCatalogMetrics(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0L
            );
        }

        public BigDecimal getMonthlyTurnover() {
            return monthlyTurnover;
        }

        public BigDecimal getReferencePrice() {
            return referencePrice;
        }

        public BigDecimal getTrustedSupply() {
            return trustedSupply;
        }

        public BigDecimal getPriceDropRatio() {
            return priceDropRatio;
        }

        public int getActiveSellerCount() {
            return activeSellerCount;
        }

        public long getLatestSellOrderCreatedAt() {
            return latestSellOrderCreatedAt;
        }

        public double getScore() {
            return score;
        }
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
