package com.github.exchange.gui;

import com.github.exchange.model.Order;
import com.github.exchange.model.Trade;

public final class ExchangeGUIHistoryViewTest {
    private static final String PLAYER_UUID = "85daec4d-08cf-374a-8481-31ab1e2c12e8";
    private static final String OTHER_UUID = "11bd3951-0e47-32ac-baea-bf042b394862";

    public static void main(String[] args) {
        Order sellOrder = order(Order.OrderType.SELL);
        Order buyOrder = order(Order.OrderType.BUY);
        Trade purchased = trade(PLAYER_UUID, OTHER_UUID);
        Trade sold = trade(OTHER_UUID, PLAYER_UUID);

        assert ExchangeGUI.HistoryView.SELL_ORDERS.accepts(sellOrder);
        assert !ExchangeGUI.HistoryView.SELL_ORDERS.accepts(buyOrder);
        assert ExchangeGUI.HistoryView.BUY_ORDERS.accepts(buyOrder);
        assert !ExchangeGUI.HistoryView.BUY_ORDERS.accepts(sellOrder);
        assert ExchangeGUI.HistoryView.BUY_TRADES.accepts(PLAYER_UUID, purchased);
        assert !ExchangeGUI.HistoryView.BUY_TRADES.accepts(PLAYER_UUID, sold);
        assert ExchangeGUI.HistoryView.SELL_TRADES.accepts(PLAYER_UUID, sold);
        assert !ExchangeGUI.HistoryView.SELL_TRADES.accepts(PLAYER_UUID, purchased);

        assert ExchangeGUI.HistoryView.SELL_ORDERS.next() == ExchangeGUI.HistoryView.BUY_ORDERS;
        assert ExchangeGUI.HistoryView.BUY_ORDERS.next() == ExchangeGUI.HistoryView.BUY_TRADES;
        assert ExchangeGUI.HistoryView.BUY_TRADES.next() == ExchangeGUI.HistoryView.SELL_TRADES;
        assert ExchangeGUI.HistoryView.SELL_TRADES.next() == ExchangeGUI.HistoryView.SELL_ORDERS;
    }

    private static Order order(Order.OrderType type) {
        Order order = new Order();
        order.setOrderType(type);
        return order;
    }

    private static Trade trade(String buyerUuid, String sellerUuid) {
        Trade trade = new Trade();
        trade.setBuyerUuid(buyerUuid);
        trade.setSellerUuid(sellerUuid);
        return trade;
    }
}
