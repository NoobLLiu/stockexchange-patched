package com.github.exchange.util;

import java.math.BigDecimal;

public final class BuyEscrowPolicyTest {
    public static void main(String[] args) {
        String marker = BuyEscrowPolicy.marker(new BigDecimal("10.0"));
        assert new BigDecimal("1100.00").equals(
            BuyEscrowPolicy.reserve(new BigDecimal("1000"), new BigDecimal("10.0"))
        );
        assert new BigDecimal("550.00").equals(
            BuyEscrowPolicy.required(new BigDecimal("500"), marker)
        );
        assert new BigDecimal("20.00").equals(
            BuyEscrowPolicy.matchedTax(new BigDecimal("200"), marker)
        );
        assert BigDecimal.ZERO.setScale(2).equals(
            BuyEscrowPolicy.legacyRefundTax(
                new BigDecimal("500"), marker, new BigDecimal("10.0")
            )
        );
        assert new BigDecimal("50.00").equals(
            BuyEscrowPolicy.legacyRefundTax(
                new BigDecimal("500"), null, new BigDecimal("10.0")
            )
        );
        System.out.println("BuyEscrowPolicyTest PASSED");
    }
}
