package com.github.exchange.gui;

final class TradeParticipantDisplay {
    private TradeParticipantDisplay() {
    }

    static boolean isViewer(String viewerUuid, String participantUuid) {
        return viewerUuid != null
            && participantUuid != null
            && viewerUuid.equalsIgnoreCase(participantUuid);
    }

    static String format(String viewerUuid, String participantUuid, String participantName) {
        if (isViewer(viewerUuid, participantUuid)) {
            return "自己";
        }
        if (participantName != null && !participantName.isBlank()) {
            return participantName;
        }
        return "未知玩家 (" + shortUuid(participantUuid) + ")";
    }

    private static String shortUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "未知 UUID";
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) + "..." : uuid;
    }
}
