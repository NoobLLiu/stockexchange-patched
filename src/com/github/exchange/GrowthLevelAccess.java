package com.github.exchange;

import cn.gmzc.titles.api.GrowthUnlocks;
import cn.gmzc.titles.api.TitleLevelService;
import cn.gmzc.titles.api.TitleLevelServices;
import java.util.UUID;
import org.bukkit.entity.Player;

final class GrowthLevelAccess {
    static final int REQUIRED_LEVEL = GrowthUnlocks.MARKET_LEVEL;

    private GrowthLevelAccess() {
    }

    static int level(Player player) {
        return level(player == null ? null : player.getUniqueId());
    }

    static int level(UUID playerUuid) {
        if (playerUuid == null) {
            return 0;
        }
        TitleLevelService service = TitleLevelServices.get();
        if (service == null) {
            return 0;
        }
        try {
            return Math.max(0, service.getLevel(playerUuid));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static boolean restricted(Player player) {
        return player != null
            && level(player.getUniqueId()) < REQUIRED_LEVEL;
    }

    static boolean restricted(UUID playerUuid) {
        return playerUuid != null
            && level(playerUuid) < REQUIRED_LEVEL;
    }
}
