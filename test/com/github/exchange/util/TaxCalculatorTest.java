package com.github.exchange.util;

import java.math.BigDecimal;

public final class TaxCalculatorTest {
    public static void main(String[] args) {
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal rate = new BigDecimal("10");

        assert TaxCalculator.tax(amount, rate).equals(new BigDecimal("10.00"));
        assert TaxCalculator.afterTax(amount, rate).equals(new BigDecimal("90.00"));
        assert TaxCalculator.withTax(amount, rate).equals(new BigDecimal("110.00"));
        assert TaxCalculator.afterTax(new BigDecimal("1000"), rate).equals(new BigDecimal("900.00"));
        assert TaxCalculator.withTax(new BigDecimal("1000"), rate).equals(new BigDecimal("1100.00"));
        assert TaxCalculator.tax(amount, BigDecimal.ZERO).equals(new BigDecimal("0.00"));
        assert TaxCalculator.afterTax(amount, new BigDecimal("100")).equals(new BigDecimal("0.00"));
        assert TaxCalculator.normalizePercent(new BigDecimal("-1")).equals(BigDecimal.ZERO);
        assert TaxCalculator.normalizePercent(new BigDecimal("101")).equals(new BigDecimal("100"));
    }
}
