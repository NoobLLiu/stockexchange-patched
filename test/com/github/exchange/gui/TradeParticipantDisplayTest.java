package com.github.exchange.gui;

public final class TradeParticipantDisplayTest {
    private static final String VIEWER_UUID = "85daec4d-08cf-374a-8481-31ab1e2c12e8";

    public static void main(String[] args) {
        assert TradeParticipantDisplay.format(
            VIEWER_UUID.toUpperCase(),
            VIEWER_UUID,
            "REKINGDLE"
        ).equals("自己") : "buyer matching the viewer must be labeled as self";

        assert TradeParticipantDisplay.format(
            VIEWER_UUID,
            VIEWER_UUID.toUpperCase(),
            "REKINGDLE"
        ).equals("自己") : "seller matching the viewer must be labeled as self";

        assert TradeParticipantDisplay.format(
            VIEWER_UUID,
            "11bd3951-0e47-32ac-baea-bf042b394862",
            "MarketSeller"
        ).equals("MarketSeller") : "known counterpart must use the resolved player name";

        assert TradeParticipantDisplay.format(
            VIEWER_UUID,
            "not-a-valid-uuid",
            null
        ).equals("未知玩家 (not-a-va...)") : "unknown or invalid UUID must use a safe fallback";

        assert TradeParticipantDisplay.format(VIEWER_UUID, null, null).equals("未知玩家 (未知 UUID)")
            : "missing UUID must not throw";
    }
}
