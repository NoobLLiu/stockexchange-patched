package com.github.exchange.warehouse;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Selects the single authoritative order for one warehouse item during
 * restart recovery.
 */
public final class WarehouseOrderRecoveryPolicy {
    private WarehouseOrderRecoveryPolicy() {
    }

    public static int chooseAuthoritative(
        Collection<Integer> candidateOrderIds,
        Integer persistedOrderId,
        Collection<Integer> lockedOrderIds
    ) {
        Set<Integer> candidates = requirePositiveIds(
            candidateOrderIds,
            "candidate order"
        );
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one candidate order is required"
            );
        }
        Set<Integer> locked = requirePositiveIds(
            lockedOrderIds,
            "locked order"
        );
        locked.retainAll(candidates);
        if (locked.size() > 1) {
            throw new IllegalStateException(
                "multiple settlement-locked warehouse orders conflict"
            );
        }
        if (!locked.isEmpty()) {
            return locked.iterator().next();
        }
        if (persistedOrderId != null && candidates.contains(persistedOrderId)) {
            return persistedOrderId;
        }
        return candidates.stream().mapToInt(Integer::intValue).min()
            .orElseThrow();
    }

    /**
     * Returns every order id that a physical warehouse recovery must inspect.
     * The persisted mapping, source-tagged escrows and source-tagged orders are
     * each independently authoritative for detecting incomplete earlier writes.
     */
    public static Set<Integer> collectReferencedIds(
        Collection<Integer> persistedOrderIds,
        Collection<Integer> escrowOrderIds,
        Collection<Integer> sourceOrderIds
    ) {
        Set<Integer> result = new LinkedHashSet<Integer>();
        addPositiveIds(result, persistedOrderIds, "persisted order");
        addPositiveIds(result, escrowOrderIds, "warehouse escrow");
        addPositiveIds(result, sourceOrderIds, "warehouse order");
        return result;
    }

    private static Set<Integer> requirePositiveIds(
        Collection<Integer> orderIds,
        String label
    ) {
        Set<Integer> result = new LinkedHashSet<Integer>();
        addPositiveIds(result, orderIds, label);
        return result;
    }

    private static void addPositiveIds(
        Set<Integer> destination,
        Collection<Integer> orderIds,
        String label
    ) {
        if (orderIds == null) {
            return;
        }
        for (Integer orderId : orderIds) {
            if (orderId == null || orderId <= 0) {
                throw new IllegalArgumentException(label + " id must be positive");
            }
            destination.add(orderId);
        }
    }
}
