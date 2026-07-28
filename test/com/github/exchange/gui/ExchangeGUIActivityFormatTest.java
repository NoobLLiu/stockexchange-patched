package com.github.exchange.gui;

public final class ExchangeGUIActivityFormatTest {
    public static void main(String[] args) {
        assert ExchangeGUI.formatActivity(12.3).equals("12.3");
        assert ExchangeGUI.formatActivity(12.35).equals("12.4");
        assert ExchangeGUI.formatActivity(-0.04).equals("0.0");
        assert ExchangeGUI.formatActivity(Double.NaN).equals("0.0");
    }
}
