package com.github.exchange.util;

public final class SpecialCategoryTest {
    public static void main(String[] args) {
        assert SpecialCategory.compareMarketPagePriority(
            SpecialCategory.ENCHANTED_BOOK,
            null
        ) < 0 : "special categories must be pinned above ordinary market items";
        assert SpecialCategory.compareMarketPagePriority(
            null,
            SpecialCategory.MUSIC_DISC
        ) > 0 : "ordinary market items must follow all special categories";
        assert SpecialCategory.compareMarketPagePriority(
            SpecialCategory.ENCHANTED_BOOK,
            SpecialCategory.ARMOR_AND_TOOLS
        ) < 0 : "special categories must have a stable market order";
        assert SpecialCategory.compareMarketPagePriority(
            SpecialCategory.ARMOR_AND_TOOLS,
            SpecialCategory.POTION
        ) < 0;
        assert SpecialCategory.compareMarketPagePriority(
            SpecialCategory.POTION,
            SpecialCategory.MUSIC_DISC
        ) < 0;
        assert SpecialCategory.compareMarketPagePriority(
            SpecialCategory.MUSIC_DISC,
            SpecialCategory.MUSIC_DISC
        ) == 0;
    }
}
