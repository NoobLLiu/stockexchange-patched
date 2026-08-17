package com.github.exchange.gui;

import com.github.exchange.model.ExchangeItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 添加求购的搜索结果目录：合并原版物品库与市场已注册（含黏液科技）物品。
 *
 * <p>支持模糊/近似匹配：先做完整子串匹配，再把关键词按空白拆词，
 * 任意词命中也可作为弱匹配；排序为精确命中 &gt; 前缀 &gt; 全词 &gt; 子串/单词。
 * 目录中已有等价品种（同材质、同显示名）时不再重复展示原版条目。</p>
 */
public final class BuySearchCatalog {

    /** 单个可求购候选：来自原版物品库或市场已注册条目。 */
    public static final class Source {
        public final String id;
        public final String displayName;
        public final String material;
        public final boolean catalog;
        public final ExchangeItem marketItem;
        int score;

        public Source(String id, String displayName, String material, boolean catalog, ExchangeItem marketItem) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.catalog = catalog;
            this.marketItem = marketItem;
        }
    }

    private BuySearchCatalog() {
    }

    /** 返回排序后的可求购结果；无匹配时返回空列表。 */
    public static List<Source> search(String query, List<Source> vanillaSources, List<Source> catalogSources) {
        String keyword = MarketListingSearch.normalize(query);
        List<Source> results = new ArrayList<Source>();
        if (keyword.isEmpty()) {
            return results;
        }
        Map<String, Source> byMaterial = new HashMap<String, Source>();
        for (Source catalogSource : catalogSources) {
            if (catalogSource == null) {
                continue;
            }
            int score = score(keyword, catalogSource);
            if (score < 0) {
                continue;
            }
            byMaterial.putIfAbsent(keyMaterial(catalogSource), catalogSource);
            results.add(catalogSource);
            catalogSource.score = score;
        }
        for (Source vanillaSource : vanillaSources) {
            if (vanillaSource == null) {
                continue;
            }
            int score = score(keyword, vanillaSource);
            if (score < 0) {
                continue;
            }
            if (byMaterial.containsKey(keyMaterial(vanillaSource))) {
                continue;
            }
            vanillaSource.score = score;
            results.add(vanillaSource);
        }
        results.sort((a, b) -> {
            int byScore = Integer.compare(a.score, b.score);
            if (byScore != 0) {
                return byScore;
            }
            int byName = MarketListingSearch.normalize(a.displayName)
                .compareTo(MarketListingSearch.normalize(b.displayName));
            if (byName != 0) {
                return byName;
            }
            return a.id.compareTo(b.id);
        });
        return results;
    }

    private static int score(String keyword, Source source) {
        String name = MarketListingSearch.normalize(source.displayName);
        String id = MarketListingSearch.normalize(source.id);
        String material = MarketListingSearch.normalize(source.material);
        if (id.equals(keyword) || name.equals(keyword) || material.equals(keyword)) {
            return 0;
        }
        if (id.startsWith(keyword) || name.startsWith(keyword) || material.startsWith(keyword)) {
            return 1;
        }
        String[] words = keyword.split(" ");
        boolean any = false;
        boolean all = true;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            boolean hit = name.contains(word) || id.contains(word) || material.contains(word);
            any = any || hit;
            all = all && hit;
        }
        if (all) {
            return 2;
        }
        if (any) {
            return 3;
        }
        return -1;
    }

    private static String keyMaterial(Source source) {
        String material = source.material == null ? "" : source.material.toUpperCase(Locale.ROOT);
        return material.isEmpty() ? "?" + source.id : material;
    }
}