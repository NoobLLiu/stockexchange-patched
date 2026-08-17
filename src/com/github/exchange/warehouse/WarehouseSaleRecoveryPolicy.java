package com.github.exchange.warehouse;

final class WarehouseSaleRecoveryPolicy {
    private WarehouseSaleRecoveryPolicy() {
    }

    static Action decide(
        Decision decision,
        Snapshot snapshot,
        boolean buyOrderAdvanced,
        boolean sellOrderAdvanced
    ) {
        if (decision == null || snapshot == null) {
            return Action.QUARANTINE;
        }
        return switch (decision) {
            case COMMIT -> snapshot == Snapshot.CONFLICT
                ? Action.QUARANTINE
                : Action.APPLY_AFTER_AND_CLEAR;
            case ROLLBACK -> snapshot == Snapshot.CONFLICT
                ? Action.QUARANTINE
                : Action.APPLY_BEFORE_AND_CLEAR;
            case IN_DOUBT -> Action.QUARANTINE;
            case PREPARED -> snapshot == Snapshot.BEFORE
                    && !buyOrderAdvanced
                    && !sellOrderAdvanced
                ? Action.CLEAR_UNTOUCHED
                : Action.QUARANTINE;
        };
    }

    enum Decision {
        PREPARED,
        COMMIT,
        ROLLBACK,
        IN_DOUBT
    }

    enum Snapshot {
        BEFORE,
        AFTER,
        CONFLICT
    }

    enum Action {
        CLEAR_UNTOUCHED,
        APPLY_BEFORE_AND_CLEAR,
        APPLY_AFTER_AND_CLEAR,
        QUARANTINE
    }
}
