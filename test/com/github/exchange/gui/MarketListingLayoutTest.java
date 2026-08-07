package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.util.ArrayList;
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

        List<MarketListingLayout.Slot> unstackable = MarketListingLayout.expand(List.of(order(3, 3, 0)), 1);
        assert unstackable.size() == 3 : "unstackable items must occupy one slot each";
        assert unstackable.stream().allMatch(slot -> slot.amount() == 1);

        assert MarketListingLayout.pageCount(slotsFor(45), 45) == 1;
        assert MarketListingLayout.pageCount(slotsFor(46), 45) == 2;
        assert MarketListingLayout.pageCount(slotsFor(90), 45) == 2;
    }

    private static Order order(int id, int quantity, int filledQty) {
        Order order = new Order();
        order.setId(id);
        order.setQuantity(quantity);
        order.setFilledQty(filledQty);
        return order;
    }

    private static List<MarketListingLayout.Slot> slotsFor(int count) {
        List<Order> orders = new ArrayList<Order>();
        for (int i = 0; i < count; ++i) {
            orders.add(order(i + 1, 1, 0));
        }
        return MarketListingLayout.expand(orders);
    }
}
