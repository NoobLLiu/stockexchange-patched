package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.util.ArrayList;
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
            if (order == null) {
                continue;
            }
            int remaining = order.getRemainingQty();
            while (remaining > 0) {
                int amount = Math.min(displayLimit, remaining);
                slots.add(new Slot(order, amount));
                remaining -= amount;
            }
        }
        return slots;
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
