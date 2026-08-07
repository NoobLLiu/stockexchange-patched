package com.github.exchange.manager;

import com.github.exchange.model.Order;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class SupplyPlanner {
    private SupplyPlanner() {
    }

    public static Plan plan(int available, List<Order> orders) {
        int availableQuantity = Math.max(0, available);
        int remaining = availableQuantity;
        BigDecimal grossAmount = BigDecimal.ZERO;
        List<Order> sortedOrders = new ArrayList<Order>();
        if (orders != null) {
            for (Order order : orders) {
                if (order != null
                    && order.getOrderType() == Order.OrderType.BUY
                    && order.isActiveForCalculation()
                    && order.getRemainingQty() > 0
                    && order.getPrice() != null) {
                    sortedOrders.add(order);
                }
            }
        }
        sortedOrders.sort(SupplyPlanner::comparePriority);

        List<Allocation> allocations = new ArrayList<Allocation>();
        for (Order order : sortedOrders) {
            if (remaining <= 0) {
                break;
            }
            int quantity = Math.min(remaining, order.getRemainingQty());
            if (quantity <= 0) {
                continue;
            }
            allocations.add(new Allocation(order, quantity));
            grossAmount = grossAmount.add(
                order.getPrice().multiply(BigDecimal.valueOf(quantity))
            );
            remaining -= quantity;
        }
        return new Plan(
            availableQuantity,
            availableQuantity - remaining,
            grossAmount,
            List.copyOf(allocations)
        );
    }

    private static int comparePriority(Order left, Order right) {
        int priceOrder = right.getPrice().compareTo(left.getPrice());
        if (priceOrder != 0) {
            return priceOrder;
        }
        Timestamp leftCreated = left.getCreatedAt();
        Timestamp rightCreated = right.getCreatedAt();
        if (leftCreated != null || rightCreated != null) {
            if (leftCreated == null) {
                return 1;
            }
            if (rightCreated == null) {
                return -1;
            }
            int createdOrder = leftCreated.compareTo(rightCreated);
            if (createdOrder != 0) {
                return createdOrder;
            }
        }
        return Integer.compare(left.getId(), right.getId());
    }

    public record Allocation(Order order, int quantity) {
    }

    public record Plan(
        int availableQuantity,
        int matchedQuantity,
        BigDecimal grossAmount,
        List<Allocation> allocations
    ) {
        public BigDecimal taxAmount(BigDecimal ratePercent) {
            BigDecimal total = BigDecimal.ZERO;
            for (Allocation allocation : this.allocations) {
                BigDecimal amount = allocation.order().getPrice()
                    .multiply(BigDecimal.valueOf(allocation.quantity()));
                total = total.add(com.github.exchange.util.TaxCalculator.tax(amount, ratePercent));
            }
            return total;
        }
    }
}
