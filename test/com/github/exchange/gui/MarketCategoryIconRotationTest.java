package com.github.exchange.gui;

import java.util.Arrays;
import java.util.List;

public final class MarketCategoryIconRotationTest {
    public static void main(String[] args) {
        List<String> icons = MarketCategoryIconRotation.distinctStableIconKeys(Arrays.asList(
            "bow",
            "diamond_sword",
            "bow",
            "",
            null,
            "diamond_chestplate",
            "diamond_sword"
        ));
        assert icons.equals(Arrays.asList("bow", "diamond_sword", "diamond_chestplate"))
            : "category icons must be unique while preserving stable sell-order order";
        assert MarketCategoryIconRotation.indexForSecond(0L, icons.size()) == 0;
        assert MarketCategoryIconRotation.indexForSecond(1L, icons.size()) == 1;
        assert MarketCategoryIconRotation.indexForSecond(2L, icons.size()) == 2;
        assert MarketCategoryIconRotation.indexForSecond(3L, icons.size()) == 0;
        assert MarketCategoryIconRotation.indexForSecond(99L, 0) == -1;
    }
}
