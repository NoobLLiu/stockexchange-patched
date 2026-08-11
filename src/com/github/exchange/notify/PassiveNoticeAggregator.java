package com.github.exchange.notify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 被动成交提醒聚合器：售出按（买家+物品）累加数量与到账金额，到货按物品累加数量。
 * 不依赖 Bukkit 调度，便于单元测试；可序列化到 offline-notices.yml。
 */
public class PassiveNoticeAggregator {
    private static final class SoldEntry {
        private final String buyerKey;
        private final String itemName;
        private int quantity;
        private BigDecimal amount = BigDecimal.ZERO;

        SoldEntry(String buyerKey, String itemName) {
            this.buyerKey = buyerKey;
            this.itemName = itemName;
        }
    }

    private final Map<String, SoldEntry> sold = new LinkedHashMap<String, SoldEntry>();
    private final Map<String, Integer> arrived = new LinkedHashMap<String, Integer>();
    private final List<String> legacy = new ArrayList<String>();

    public void addSold(String buyerKey, String itemName, int quantity, BigDecimal amount) {
        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        String key = (buyerKey == null ? "?" : buyerKey) + '\u0001' + itemName;
        SoldEntry entry = this.sold.get(key);
        if (entry == null) {
            entry = new SoldEntry(buyerKey == null ? "?" : buyerKey, itemName);
            this.sold.put(key, entry);
        }
        entry.quantity += quantity;
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            entry.amount = entry.amount.add(amount);
        }
    }

    public void addArrived(String itemName, int quantity) {
        if (itemName == null || itemName.isBlank() || quantity <= 0) {
            return;
        }
        this.arrived.merge(itemName, quantity, Integer::sum);
    }

    public void addLegacy(String message) {
        if (message != null && !message.isBlank()) {
            this.legacy.add(message);
        }
    }

    public boolean isEmpty() {
        return this.sold.isEmpty() && this.arrived.isEmpty() && this.legacy.isEmpty();
    }

    public void merge(PassiveNoticeAggregator other) {
        if (other == null) {
            return;
        }
        for (SoldEntry entry : other.sold.values()) {
            this.addSold(entry.buyerKey, entry.itemName, entry.quantity, entry.amount);
        }
        for (Map.Entry<String, Integer> entry : other.arrived.entrySet()) {
            this.addArrived(entry.getKey(), entry.getValue());
        }
        this.legacy.addAll(other.legacy);
    }

    public List<String> buildLines(String currencyName) {
        List<String> lines = new ArrayList<String>();
        String currency = currencyName == null ? "" : currencyName;
        for (SoldEntry entry : this.sold.values()) {
            lines.add("\u00a7a\u4f60\u7684\u300c" + entry.itemName + "\u300d x" + entry.quantity
                + " \u5df2\u552e\u51fa\uff0c\u83b7\u5f97 " + entry.amount.toPlainString()
                + " " + currency + "\u3002");
        }
        for (Map.Entry<String, Integer> entry : this.arrived.entrySet()) {
            lines.add("\u00a7a\u4f60\u6c42\u8d2d\u7684\u300c" + entry.getKey() + "\u300d x"
                + entry.getValue() + " \u5df2\u5230\u8d27\u3002");
        }
        lines.addAll(this.legacy);
        return lines;
    }

    public void toYaml(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        int i = 0;
        for (SoldEntry entry : this.sold.values()) {
            String path = "sold." + i++;
            section.set(path + ".buyer", entry.buyerKey);
            section.set(path + ".item", entry.itemName);
            section.set(path + ".quantity", entry.quantity);
            section.set(path + ".amount", entry.amount.toPlainString());
        }
        i = 0;
        for (Map.Entry<String, Integer> entry : this.arrived.entrySet()) {
            String path = "arrived." + i++;
            section.set(path + ".item", entry.getKey());
            section.set(path + ".quantity", entry.getValue());
        }
        section.set("legacy", this.legacy);
    }

    public static PassiveNoticeAggregator fromYaml(ConfigurationSection section) {
        PassiveNoticeAggregator aggregator = new PassiveNoticeAggregator();
        if (section == null) {
            return aggregator;
        }
        ConfigurationSection soldSection = section.getConfigurationSection("sold");
        if (soldSection != null) {
            for (String key : soldSection.getKeys(false)) {
                ConfigurationSection entrySection = soldSection.getConfigurationSection(key);
                if (entrySection == null) {
                    continue;
                }
                BigDecimal amount;
                try {
                    amount = new BigDecimal(entrySection.getString("amount", "0"));
                }
                catch (NumberFormatException ignored) {
                    amount = BigDecimal.ZERO;
                }
                aggregator.addSold(entrySection.getString("buyer", "?"),
                    entrySection.getString("item", ""),
                    entrySection.getInt("quantity", 0), amount);
            }
        }
        ConfigurationSection arrivedSection = section.getConfigurationSection("arrived");
        if (arrivedSection != null) {
            for (String key : arrivedSection.getKeys(false)) {
                ConfigurationSection entrySection = arrivedSection.getConfigurationSection(key);
                if (entrySection == null) {
                    continue;
                }
                aggregator.addArrived(entrySection.getString("item", ""),
                    entrySection.getInt("quantity", 0));
            }
        }
        aggregator.legacy.addAll(section.getStringList("legacy"));
        return aggregator;
    }
}
