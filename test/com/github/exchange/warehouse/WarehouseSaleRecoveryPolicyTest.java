package com.github.exchange.warehouse;

import static com.github.exchange.warehouse.WarehouseSaleRecoveryPolicy.Action;
import static com.github.exchange.warehouse.WarehouseSaleRecoveryPolicy.Decision;
import static com.github.exchange.warehouse.WarehouseSaleRecoveryPolicy.Snapshot;

public final class WarehouseSaleRecoveryPolicyTest {
    public static void main(String[] args) {
        assert decide(Decision.COMMIT, Snapshot.BEFORE, false, false)
            == Action.APPLY_AFTER_AND_CLEAR;
        assert decide(Decision.COMMIT, Snapshot.AFTER, true, true)
            == Action.APPLY_AFTER_AND_CLEAR;
        assert decide(Decision.ROLLBACK, Snapshot.AFTER, false, false)
            == Action.APPLY_BEFORE_AND_CLEAR;
        assert decide(Decision.ROLLBACK, Snapshot.BEFORE, false, false)
            == Action.APPLY_BEFORE_AND_CLEAR;
        assert decide(Decision.IN_DOUBT, Snapshot.AFTER, true, true)
            == Action.QUARANTINE;
        assert decide(Decision.PREPARED, Snapshot.BEFORE, false, false)
            == Action.CLEAR_UNTOUCHED;
        assert decide(Decision.PREPARED, Snapshot.BEFORE, true, false)
            == Action.QUARANTINE;
        assert decide(Decision.PREPARED, Snapshot.AFTER, false, false)
            == Action.QUARANTINE;
        assert decide(Decision.COMMIT, Snapshot.CONFLICT, true, true)
            == Action.QUARANTINE;
        assert decide(Decision.ROLLBACK, Snapshot.CONFLICT, false, false)
            == Action.QUARANTINE;
    }

    private static Action decide(
        Decision decision,
        Snapshot snapshot,
        boolean buyOrderAdvanced,
        boolean sellOrderAdvanced
    ) {
        return WarehouseSaleRecoveryPolicy.decide(
            decision,
            snapshot,
            buyOrderAdvanced,
            sellOrderAdvanced
        );
    }
}
