package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.util.Arrays;
import java.util.List;

public final class MarketListingLayoutTest {
    public static void main(String[] args) {
        Order large = order(1, 130, 0);
        Order partial = order(2, 100, 1);
        List<MarketListingLayout.Slot> slots = MarketListingLayout.expand(Arrays.asList(large, partial));

        assert slots.size() == 5 : "130 and 99 items should occupy five slots";
        assert slots.get(0).order() == large && slots.get(0).amount() == 64;
        assert slots.get(1).order() == large && slots.get(1).amount() == 64;
        assert slots.get(2).order() == large && slots.get(2).amount() == 2;
        assert slots.get(3).order() == partial && slots.get(3).amount() == 64;
        assert slots.get(4).order() == partial && slots.get(4).amount() == 35;
    }

    private static Order order(int id, int quantity, int filledQty) {
        Order order = new Order();
        order.setId(id);
        order.setQuantity(quantity);
        order.setFilledQty(filledQty);
        return order;
    }
}
