package com.github.exchange.warehouse;

import java.util.List;
import java.util.Set;

public final class WarehouseOrderRecoveryPolicyTest {
    public static void main(String[] args) {
        assert WarehouseOrderRecoveryPolicy.chooseAuthoritative(
            List.of(13, 7, 19),
            13,
            Set.of()
        ) == 13;
        assert WarehouseOrderRecoveryPolicy.chooseAuthoritative(
            List.of(13, 7, 19),
            13,
            Set.of(19)
        ) == 19;
        assert WarehouseOrderRecoveryPolicy.chooseAuthoritative(
            List.of(13, 7, 19),
            99,
            Set.of()
        ) == 7;
        assertThrows(() -> WarehouseOrderRecoveryPolicy.chooseAuthoritative(
            List.of(13, 7),
            13,
            Set.of(13, 7)
        ));
        assertThrows(() -> WarehouseOrderRecoveryPolicy.chooseAuthoritative(
            List.of(0),
            null,
            Set.of()
        ));
        assert WarehouseOrderRecoveryPolicy.collectReferencedIds(
            List.of(13),
            List.of(19, 13),
            List.of(7, 19)
        ).equals(Set.of(13, 19, 7))
            : "recovery must inspect mapping-only, escrow-only and order-only ids";
        assertThrows(() -> WarehouseOrderRecoveryPolicy.collectReferencedIds(
            List.of(13),
            List.of(0),
            Set.of()
        ));
        System.out.println("WarehouseOrderRecoveryPolicyTest PASSED");
    }

    private static void assertThrows(Runnable action) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            thrown = true;
        }
        assert thrown;
    }
}
