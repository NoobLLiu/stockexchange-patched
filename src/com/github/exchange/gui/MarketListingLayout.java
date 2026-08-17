package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MarketListingLayout {
    public static final int MAX_DISPLAY_AMOUNT = 64;

    private MarketListingLayout() {
    }

    public static List<Slot> expand(List<Order> orders) {
        return expand(orders, MAX_DISPLAY_AMOUNT);
    }

    public static List<Slot> expand(List<Order> orders, int maxDisplayAmount) {
        int displayLimit = Math.max(1, Math.min(MAX_DISPLAY_AMOUNT, maxDisplayAmount));
        List<Slot> slots = new ArrayList<Slot>();
        for (Order order : orders) {
            slots.addAll(expand(order, displayLimit));
        }
        return slots;
    }

    public static List<Slot> expand(Order order, int maxDisplayAmount) {
        int displayLimit = Math.max(1, Math.min(MAX_DISPLAY_AMOUNT, maxDisplayAmount));
        List<Slot> slots = new ArrayList<Slot>();
        if (order == null) {
            return slots;
        }
        int remaining = order.getRemainingQty();
        while (remaining > 0) {
            int amount = Math.min(displayLimit, remaining);
            slots.add(new Slot(order, amount));
            remaining -= amount;
        }
        return slots;
    }

    public static List<Order> sortBuyOrders(List<Order> orders) {
        List<Order> sorted = new ArrayList<Order>();
        if (orders != null) {
            for (Order order : orders) {
                if (order != null) {
                    sorted.add(order);
                }
            }
        }
        sorted.sort(MarketListingLayout::compareBuyOrders);
        return sorted;
    }

    public static int compareBuyOrders(Order left, Order right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Comparator.comparing(
            Order::getPrice,
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(
            Order::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparingInt(Order::getId).compare(left, right);
    }

    public static int pageCount(List<Slot> slots, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        long slotCount = slots == null ? 0L : slots.size();
        return (int)Math.max(1L, (slotCount + pageSize - 1L) / pageSize);
    }

    public record Slot(Order order, int amount) {
    }
}
