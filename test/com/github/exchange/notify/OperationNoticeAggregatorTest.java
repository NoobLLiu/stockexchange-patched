package com.github.exchange.notify;

import java.math.BigDecimal;
import java.util.List;

public final class OperationNoticeAggregatorTest {
    public static void main(String[] args) {
        OperationNoticeAggregator aggregator = new OperationNoticeAggregator();
        aggregator.addPurchase("凋灵骷髅头颅", 1, money("2000"), money("200"), money("2200"));
        aggregator.addPurchase("凋灵骷髅头颅", 4, money("8000"), money("800"), money("8800"));
        aggregator.addSale("绿宝石", 2, money("1000"), money("100"), money("900"));
        aggregator.addSale("绿宝石", 3, money("1500"), money("150"), money("1350"));
        aggregator.addListing("钻石", 2);
        aggregator.addListing("金锭", 3);
        aggregator.addListing("钻石", 1);

        List<String> lines = aggregator.buildLines("星光点");
        assert lines.size() == 3 : lines;
        assert lines.get(0).contains("凋灵骷髅头颅」x5") : lines.get(0);
        assert lines.get(0).contains("成交价: 10000") : lines.get(0);
        assert lines.get(0).contains("交易税: 1000") : lines.get(0);
        assert lines.get(0).contains("实际扣款: 11000") : lines.get(0);
        assert lines.get(1).contains("绿宝石」x5") : lines.get(1);
        assert lines.get(1).contains("实际到账: 2250") : lines.get(1);
        assert lines.get(2).contains("钻石 x3、金锭 x3") : lines.get(2);
        System.out.println("OperationNoticeAggregatorTest PASSED");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
