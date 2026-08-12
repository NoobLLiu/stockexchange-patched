package com.github.exchange.gui;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.sql.Timestamp;
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

        Order low = buyOrder(4, "10.00", 20L);
        Order highLater = buyOrder(5, "25.00", 130, 0, 30L);
        Order highEarlier = buyOrder(6, "25.00", 130, 0, 10L);
        List<Order> sorted = MarketListingLayout.sortBuyOrders(
            Arrays.asList(low, highLater, highEarlier)
        );
        assert sorted.equals(Arrays.asList(highEarlier, highLater, low))
            : "BUY menu orders must be ordered by unit price from high to low";
        List<MarketListingLayout.Slot> sortedSlots = MarketListingLayout.expand(sorted, 64);
        assert sortedSlots.get(0).order() == highEarlier
            && sortedSlots.get(1).order() == highEarlier
            && sortedSlots.get(2).order() == highEarlier
            && sortedSlots.get(3).order() == highLater
            : "every stack of a higher-priced BUY order must precede lower-ranked orders";

        List<MarketListingLayout.Slot> splitOrder = MarketListingLayout.expand(highEarlier, 16);
        assert splitOrder.size() == 9
            && splitOrder.stream().allMatch(slot -> slot.amount() > 0 && slot.amount() <= 16)
            && splitOrder.stream().map(MarketListingLayout.Slot::order).allMatch(order -> order == highEarlier)
            && splitOrder.stream().mapToInt(MarketListingLayout.Slot::amount).sum() == 130
            : "a BUY order must retain its order identity while splitting into valid display stacks";

    }

    private static Order order(int id, int quantity, int filledQty) {
        Order order = new Order();
        order.setId(id);
        order.setQuantity(quantity);
        order.setFilledQty(filledQty);
        return order;
    }

    private static Order buyOrder(int id, String price, long createdAt) {
        return buyOrder(id, price, 1, 0, createdAt);
    }

    private static Order buyOrder(int id, String price, int quantity, int filledQty, long createdAt) {
        Order order = order(id, quantity, filledQty);
        order.setPrice(new BigDecimal(price));
        order.setCreatedAt(new Timestamp(createdAt));
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
