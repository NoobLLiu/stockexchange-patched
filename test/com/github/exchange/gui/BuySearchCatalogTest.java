package com.github.exchange.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BuySearchCatalogTest {
    public static void main(String[] args) {
        // 目录已有等价品种时，原版条目不重复出现（去重 + 目录优先）
        List<BuySearchCatalog.Source> vanilla = new ArrayList<BuySearchCatalog.Source>();
        vanilla.add(vanilla("diamond_sword", "\u94bb\u77f3\u5251"));
        List<BuySearchCatalog.Source> catalog = new ArrayList<BuySearchCatalog.Source>();
        catalog.add(catalogEntry(7, "\u94bb\u77f3\u5251", "DIAMOND_SWORD"));
        List<BuySearchCatalog.Source> results = BuySearchCatalog.search("\u94bb\u77f3\u5251", vanilla, catalog);
        assert results.size() == 1;
        assert results.get(0).catalog;
        assert "7".equals(results.get(0).id);

        // 精确 > 前缀 > 包含；不同材质原版条目保留
        vanilla.clear();
        vanilla.add(vanilla("diamond", "\u94bb\u77f3"));
        vanilla.add(vanilla("diamond_sword", "\u94bb\u77f3\u5251"));
        vanilla.add(vanilla("diamond_pickaxe", "\u94bb\u77f3\u9556"));
        catalog.clear();
        catalog.add(catalogEntry(9, "\u9644\u9b54\u94bb\u77f3\u5251", "DIAMOND_SWORD"));
        results = BuySearchCatalog.search("\u94bb\u77f3", vanilla, catalog);
        assert results.size() == 3;
        assert "\u94bb\u77f3".equals(results.get(0).displayName);
        assert "\u94bb\u77f3\u9556".equals(results.get(1).displayName);
        assert "\u9644\u9b54\u94bb\u77f3\u5251".equals(results.get(2).displayName);

        // 近似：按空白拆词，任意词命中
        vanilla.clear();
        vanilla.add(vanilla("diamond_sword", "\u94bb\u77f3\u5251"));
        catalog.clear();
        results = BuySearchCatalog.search("sword pick", vanilla, catalog);
        assert results.size() == 1;
        assert "diamond_sword".equals(results.get(0).id);

        // 黏液科技类目录物品按名称模糊命中
        vanilla.clear();
        catalog.clear();
        catalog.add(catalogEntry(30, "\u7535\u529b\u5934\u76d4", "IRON_HELMET"));
        results = BuySearchCatalog.search("\u7535\u529b", vanilla, catalog);
        assert results.size() == 1;
        assert results.get(0).catalog;
        assert "30".equals(results.get(0).id);

        // 空关键词 / 无匹配 -> 空
        vanilla.clear();
        vanilla.add(vanilla("carrot", "\u80e1\u841d\u535c"));
        assert BuySearchCatalog.search("", vanilla, new ArrayList<BuySearchCatalog.Source>()).isEmpty();
        assert BuySearchCatalog.search("   ", vanilla, new ArrayList<BuySearchCatalog.Source>()).isEmpty();
        assert BuySearchCatalog.search("zzzqqq_nonsense", vanilla, new ArrayList<BuySearchCatalog.Source>()).isEmpty();

        System.out.println("PASSED BuySearchCatalogTest");
    }

    private static BuySearchCatalog.Source vanilla(String id, String name) {
        return new BuySearchCatalog.Source(id, name, id.toUpperCase(Locale.ROOT), false, null);
    }

    private static BuySearchCatalog.Source catalogEntry(int id, String name, String material) {
        com.github.exchange.model.ExchangeItem item = new com.github.exchange.model.ExchangeItem();
        item.setId(id);
        item.setDisplayName(name);
        return new BuySearchCatalog.Source(String.valueOf(id), name, material, true, item);
    }
}
