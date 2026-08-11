package com.github.exchange.notify;

import java.math.BigDecimal;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

public final class PassiveNoticeAggregatorTest {
    public static void main(String[] args) {
        // 同一买家疯狂购买同一商品：数量与金额累加成一行。
        PassiveNoticeAggregator sold = new PassiveNoticeAggregator();
        sold.addSold("buyerA", "\u7164\u70ad", 1, new BigDecimal("1.00"));
        sold.addSold("buyerA", "\u7164\u70ad", 19, new BigDecimal("19.00"));
        List<String> lines = sold.buildLines("\u661f\u5149\u70b9");
        assert lines.size() == 1;
        assert lines.get(0).contains("x20");
        assert lines.get(0).contains("20.00");

        // 同一买家购买不同商品：各一行。
        PassiveNoticeAggregator multiItem = new PassiveNoticeAggregator();
        multiItem.addSold("buyerA", "\u7164\u70ad", 5, BigDecimal.ONE);
        multiItem.addSold("buyerA", "\u94bb\u77f3", 2, BigDecimal.TEN);
        assert multiItem.buildLines("\u661f\u5149\u70b9").size() == 2;

        // 不同买家购买同一商品：按买家分开，不混算。
        PassiveNoticeAggregator multiBuyer = new PassiveNoticeAggregator();
        multiBuyer.addSold("buyerA", "\u7164\u70ad", 5, BigDecimal.ONE);
        multiBuyer.addSold("buyerB", "\u7164\u70ad", 7, BigDecimal.ONE);
        assert multiBuyer.buildLines("\u661f\u5149\u70b9").size() == 2;

        // 求购到货：同物品多次供货累加为一行。
        PassiveNoticeAggregator arrived = new PassiveNoticeAggregator();
        arrived.addArrived("\u7164\u70ad", 1);
        arrived.addArrived("\u7164\u70ad", 19);
        List<String> arrivedLines = arrived.buildLines("\u661f\u5149\u70b9");
        assert arrivedLines.size() == 1;
        assert arrivedLines.get(0).contains("x20");

        // 合并两个聚合器（离线转在线或插件关闭冲刷时使用）。
        PassiveNoticeAggregator base = new PassiveNoticeAggregator();
        base.addSold("buyerA", "\u7164\u70ad", 1, BigDecimal.ONE);
        PassiveNoticeAggregator extra = new PassiveNoticeAggregator();
        extra.addSold("buyerA", "\u7164\u70ad", 2, BigDecimal.valueOf(2));
        extra.addLegacy("\u65e7\u683c\u5f0f\u79bb\u7ebf\u63d0\u793a");
        base.merge(extra);
        List<String> merged = base.buildLines("\u661f\u5149\u70b9");
        assert merged.size() == 2;
        assert merged.get(0).contains("x3");
        assert merged.get(1).contains("\u65e7\u683c\u5f0f");

        // YAML 序列化往返不丢数据。
        YamlConfiguration yaml = new YamlConfiguration();
        PassiveNoticeAggregator source = new PassiveNoticeAggregator();
        source.addSold("buyerA", "\u7164\u70ad", 20, new BigDecimal("20.00"));
        source.addArrived("\u94bb\u77f3", 3);
        source.toYaml(yaml);
        PassiveNoticeAggregator loaded = PassiveNoticeAggregator.fromYaml(yaml);
        List<String> roundTrip = loaded.buildLines("\u661f\u5149\u70b9");
        assert roundTrip.size() == 2;
        assert roundTrip.get(0).contains("x20");
        assert roundTrip.get(0).contains("20.00");
        assert roundTrip.get(1).contains("x3");

        System.out.println("PassiveNoticeAggregatorTest PASSED");
    }
}
