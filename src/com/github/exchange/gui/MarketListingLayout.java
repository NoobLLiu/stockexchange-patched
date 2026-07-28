package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.util.ArrayList;
import java.util.List;

public final class MarketListingLayout {
    public static final int MAX_DISPLAY_AMOUNT = 64;

    private MarketListingLayout() {
    }

    public static List<Slot> expand(List<Order> orders) {
        List<Slot> slots = new ArrayList<Slot>();
        for (Order order : orders) {
            if (order == null) {
                continue;
            }
            int remaining = order.getRemainingQty();
            while (remaining > 0) {
                int amount = Math.min(MAX_DISPLAY_AMOUNT, remaining);
                slots.add(new Slot(order, amount));
                remaining -= amount;
            }
        }
        return slots;
    }

    public record Slot(Order order, int amount) {
    }
}
