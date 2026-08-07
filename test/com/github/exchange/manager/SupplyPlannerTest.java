package com.github.exchange.manager;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;

public final class SupplyPlannerTest {
    public static void main(String[] args) {
        Order lower = order(2, new BigDecimal("1.00"), 100, 0, 2_000L);
        Order higher = order(1, new BigDecimal("2.00"), 150, 0, 1_000L);
        SupplyPlanner.Plan plan = SupplyPlanner.plan(200, Arrays.asList(lower, higher));

        assert plan.availableQuantity() == 200;
        assert plan.matchedQuantity() == 200;
        assert plan.allocations().size() == 2;
        assert plan.allocations().get(0).order() == higher;
        assert plan.allocations().get(0).quantity() == 150;
        assert plan.allocations().get(1).order() == lower;
        assert plan.allocations().get(1).quantity() == 50;
        assert plan.grossAmount().equals(new BigDecimal("350.00"));
        assert plan.taxAmount(new BigDecimal("10.0")).equals(new BigDecimal("35.00"));
    }

    private static Order order(
        int id,
        BigDecimal price,
        int quantity,
        int filled,
        long createdAt
    ) {
        Order order = new Order();
        order.setId(id);
        order.setOrderType(Order.OrderType.BUY);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setFilledQty(filled);
        order.setStatus(Order.OrderStatus.OPEN);
        order.setCreatedAt(new Timestamp(createdAt));
        return order;
    }
}
