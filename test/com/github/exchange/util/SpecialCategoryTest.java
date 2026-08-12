package com.github.exchange.util;

public final class SpecialCategoryTest {
    public static void main(String[] args) {
        assert SpecialCategory.values().length == 4
            : "the four aggregate categories must remain available";
        assert SpecialCategory.valueOf("ENCHANTED_BOOK") == SpecialCategory.ENCHANTED_BOOK;
        assert SpecialCategory.valueOf("ARMOR_AND_TOOLS") == SpecialCategory.ARMOR_AND_TOOLS;
        assert SpecialCategory.valueOf("POTION") == SpecialCategory.POTION;
        assert SpecialCategory.valueOf("MUSIC_DISC") == SpecialCategory.MUSIC_DISC;
    }
}
