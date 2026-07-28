package com.github.exchange.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TaxCalculator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);

    private TaxCalculator() {
    }

    public static BigDecimal normalizePercent(BigDecimal percent) {
        if (percent == null || percent.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (percent.compareTo(ONE_HUNDRED) > 0) {
            return ONE_HUNDRED;
        }
        return percent.stripTrailingZeros();
    }

    public static BigDecimal tax(BigDecimal amount, BigDecimal ratePercent) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal normalizedRate = normalizePercent(ratePercent);
        return amount.multiply(normalizedRate)
            .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal afterTax(BigDecimal amount, BigDecimal ratePercent) {
        return amount.subtract(tax(amount, ratePercent)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal withTax(BigDecimal amount, BigDecimal ratePercent) {
        return amount.add(tax(amount, ratePercent)).setScale(2, RoundingMode.HALF_UP);
    }
}
