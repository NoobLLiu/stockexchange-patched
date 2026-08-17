package com.github.exchange.gui;

import com.github.exchange.util.SpecialCategory;

public final class ExchangeGUIMarketMenuLayoutTest {
    public static void main(String[] args) {
        assert MarketMenuLayout.ITEM_PAGE_SIZE == 36
            : "market items must remain limited to the first four rows";
        assert MarketMenuLayout.SEPARATOR_START_SLOT == 36;
        assert MarketMenuLayout.SEPARATOR_END_SLOT == 42;
        for (int slot = MarketMenuLayout.SEPARATOR_START_SLOT;
             slot <= MarketMenuLayout.SEPARATOR_END_SLOT;
             ++slot) {
            assert row(slot) == 5;
            assert column(slot) >= 1 && column(slot) <= 7;
        }

        assertPosition(MarketMenuLayout.PREVIOUS_PAGE_SLOT, 5, 8, "previous page");
        assertPosition(MarketMenuLayout.NEXT_PAGE_SLOT, 5, 9, "next page");
        assertPosition(MarketMenuLayout.WAREHOUSE_SLOT, 6, 7, "warehouse configuration");
        assertPosition(MarketMenuLayout.ACTION_SLOT, 6, 8, "listing action");
        assertPosition(MarketMenuLayout.BACK_SLOT, 6, 9, "back");

        boolean[] occupied = new boolean[54];
        for (int slot = MarketMenuLayout.SEPARATOR_START_SLOT;
             slot <= MarketMenuLayout.SEPARATOR_END_SLOT;
             ++slot) {
            assert !occupied[slot];
            occupied[slot] = true;
        }
        for (int slot : new int[]{
            MarketMenuLayout.PREVIOUS_PAGE_SLOT,
            MarketMenuLayout.NEXT_PAGE_SLOT,
            MarketMenuLayout.WAREHOUSE_SLOT,
            MarketMenuLayout.ACTION_SLOT,
            MarketMenuLayout.BACK_SLOT
        }) {
            assert !occupied[slot] : "market-menu controls must not overlap";
            occupied[slot] = true;
        }

        assert "\u00a7a\u914d\u7f6e\u81ea\u52a8\u51fa\u552e\u4ed3\u5e93"
            .equals(MarketMenuLayout.SELL_WAREHOUSE_NAME);
        assert "\u00a7c\u914d\u7f6e\u6c42\u8d2d\u6536\u8d27\u4ed3\u5e93"
            .equals(MarketMenuLayout.BUY_WAREHOUSE_NAME);
        assert ExchangeGUI.functionalModelData(MarketMenuLayout.SELL_WAREHOUSE_NAME) == null
            : "sell warehouse button must retain the native chest model";
        assert ExchangeGUI.functionalModelData(MarketMenuLayout.BUY_WAREHOUSE_NAME) == null
            : "buy warehouse button must retain the native chest model";

        // 求购搜索会注册独立品种，不能沿用出售页的聚合分类过滤。
        assert ExchangeGUI.shouldHideRegularCatalogItem(false, SpecialCategory.ARMOR_AND_TOOLS);
        assert !ExchangeGUI.shouldHideRegularCatalogItem(true, SpecialCategory.ARMOR_AND_TOOLS);
        assert !ExchangeGUI.shouldHideRegularCatalogItem(
            true,
            null
        );
    }

    private static void assertPosition(int slot, int expectedRow, int expectedColumn, String label) {
        assert row(slot) == expectedRow
            : label + " must be on row " + expectedRow;
        assert column(slot) == expectedColumn
            : label + " must be in column " + expectedColumn;
    }

    private static int row(int slot) {
        return slot / 9 + 1;
    }

    private static int column(int slot) {
        return slot % 9 + 1;
    }
}
