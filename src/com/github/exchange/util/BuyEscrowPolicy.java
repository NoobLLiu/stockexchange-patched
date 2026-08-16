package com.github.exchange.util;

import java.math.BigDecimal;

/**
 * Marks new BUY escrows that reserve both principal and buyer tax.
 * Legacy escrows have no marker because their tax was collected at order creation.
 */
public final class BuyEscrowPolicy {
    private static final String PREFIX = "buy-tax-reserved:v1:";

    private BuyEscrowPolicy() {
    }

    public static String marker(BigDecimal ratePercent) {
        return PREFIX + TaxCalculator.normalizePercent(ratePercent).stripTrailingZeros().toPlainString();
    }

    public static BigDecimal reservedRate(String marker) {
        if (marker == null || !marker.startsWith(PREFIX)) {
            return null;
        }
        try {
            return TaxCalculator.normalizePercent(new BigDecimal(marker.substring(PREFIX.length())));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static BigDecimal reserve(BigDecimal principal, BigDecimal ratePercent) {
        return TaxCalculator.withTax(principal, ratePercent);
    }

    public static BigDecimal required(BigDecimal principal, String marker) {
        BigDecimal rate = reservedRate(marker);
        return rate == null ? principal : TaxCalculator.withTax(principal, rate);
    }

    public static BigDecimal matchedTax(BigDecimal matchedAmount, String marker) {
        BigDecimal rate = reservedRate(marker);
        return rate == null ? BigDecimal.ZERO.setScale(2) : TaxCalculator.tax(matchedAmount, rate);
    }

    public static BigDecimal legacyRefundTax(
        BigDecimal unfilledPrincipal,
        String marker,
        BigDecimal currentRatePercent
    ) {
        return reservedRate(marker) == null
            ? TaxCalculator.tax(unfilledPrincipal, currentRatePercent)
            : BigDecimal.ZERO.setScale(2);
    }
}
