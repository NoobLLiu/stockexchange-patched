package com.github.exchange.util;

public final class ItemDisplayNamesTest {
    public static void main(String[] args) {
        assert "钻石".equals(ItemDisplayNames.resolveTranslationKey("item.minecraft.diamond"));
        assert "白桦木板".equals(ItemDisplayNames.resolveTranslationKey("block.minecraft.birch_planks"));
        assert "橡木木板".equals(ItemDisplayNames.resolveTranslationKey("block.minecraft.oak_planks"));
        assert "橡木原木".equals(ItemDisplayNames.resolveTranslationKey("block.minecraft.oak_log"));
    }
}
