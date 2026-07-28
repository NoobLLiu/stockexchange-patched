package com.github.exchange.gui;

public final class MarketListingSearchTest {
    public static void main(String[] args) {
        assert MarketListingSearch.matches("钻石", 11, "\u00a7b钻石剑", "锋利钻石剑", "minecraft:diamond_sword");
        assert MarketListingSearch.matches("DIAMOND SWORD", 11, "钻石剑", null, "minecraft:diamond_sword");
        assert MarketListingSearch.matches("diamond_sword", 11, "钻石剑", null, "minecraft:diamond_sword");
        assert MarketListingSearch.matches("11", 11, "钻石剑", null, "minecraft:diamond_sword");
        assert MarketListingSearch.matches("锋利", 11, "钻石剑", "锋利钻石剑", "DIAMOND_SWORD");
        assert !MarketListingSearch.matches("下界合金", 11, "钻石剑", "锋利钻石剑", "DIAMOND_SWORD");
        assert "minecraft diamond sword".equals(MarketListingSearch.normalize("\u00a7bMinecraft:DIAMOND_SWORD"));
    }
}
