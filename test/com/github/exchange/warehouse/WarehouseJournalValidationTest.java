package com.github.exchange.warehouse;

import java.util.ArrayList;
import java.util.List;

public final class WarehouseJournalValidationTest {
    public static void main(String[] args) {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assert WarehouseJournalValidation.requireUuid(uuid, "id").equals(uuid);
        assert WarehouseJournalValidation.requirePositiveInt(1, "id") == 1;
        assert WarehouseJournalValidation.requireExpectedFilled(5, 2, "filled")
            == 5;

        List<String> before = emptySnapshot(27);
        List<String> after = new ArrayList<String>(before);
        after.set(0, "serialized-item");
        List<String> copiedBefore =
            WarehouseJournalValidation.copySnapshot(before, "before");
        List<String> copiedAfter =
            WarehouseJournalValidation.copySnapshot(after, "after");
        WarehouseJournalValidation.requireDistinctSnapshotPair(
            copiedBefore,
            copiedAfter,
            "journal"
        );
        assert copiedBefore.size() == 27;
        assert copiedAfter.size() == 27;

        expectFailure(() ->
            WarehouseJournalValidation.requireUuid("not-a-uuid", "id")
        );
        expectFailure(() ->
            WarehouseJournalValidation.requireUuid(
                "123e4567e89b12d3a456426614174000",
                "id"
            )
        );
        expectFailure(() ->
            WarehouseJournalValidation.requirePositiveInt(0, "id")
        );
        expectFailure(() ->
            WarehouseJournalValidation.requirePositiveInt(-1, "id")
        );
        expectFailure(() ->
            WarehouseJournalValidation.requirePositiveInt("1", "id")
        );
        expectFailure(() ->
            WarehouseJournalValidation.requireExpectedFilled(1, 2, "filled")
        );
        expectFailure(() ->
            WarehouseJournalValidation.copySnapshot(
                emptySnapshot(26),
                "before"
            )
        );
        expectFailure(() ->
            WarehouseJournalValidation.copySnapshot(
                emptySnapshot(55),
                "before"
            )
        );

        List<String> withNull = emptySnapshot(27);
        withNull.set(0, null);
        expectFailure(() ->
            WarehouseJournalValidation.copySnapshot(withNull, "before")
        );
        expectFailure(() ->
            WarehouseJournalValidation.requireDistinctSnapshotPair(
                emptySnapshot(27),
                emptySnapshot(54),
                "journal"
            )
        );
        expectFailure(() ->
            WarehouseJournalValidation.requireDistinctSnapshotPair(
                emptySnapshot(27),
                emptySnapshot(27),
                "journal"
            )
        );

        assert WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.COMMIT,
            5,
            5,
            8,
            8,
            2
        );
        assert !WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.COMMIT,
            3,
            5,
            6,
            8,
            2
        );
        assert !WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.COMMIT,
            5,
            5,
            6,
            8,
            2
        );
        assert WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.ROLLBACK,
            3,
            5,
            6,
            8,
            2
        );
        assert !WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.ROLLBACK,
            5,
            5,
            8,
            8,
            2
        );
        assert WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.PREPARED,
            5,
            5,
            6,
            8,
            2
        );
        assert WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.IN_DOUBT,
            3,
            5,
            8,
            8,
            2
        );
        assert !WarehouseJournalValidation.isRecoveryStateCompatible(
            WarehouseSaleRecoveryPolicy.Decision.PREPARED,
            4,
            5,
            6,
            8,
            2
        );
        assert !WarehouseJournalValidation.isRecoveryStateCompatible(
            null,
            3,
            5,
            6,
            8,
            2
        );
        System.out.println("WarehouseJournalValidationTest PASSED");
    }

    private static List<String> emptySnapshot(int size) {
        ArrayList<String> result = new ArrayList<String>(size);
        for (int index = 0; index < size; index++) {
            result.add("");
        }
        return result;
    }

    private static void expectFailure(Runnable action) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            failed = true;
        }
        assert failed;
    }
}
