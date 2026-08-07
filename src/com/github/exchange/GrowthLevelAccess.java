package com.github.exchange;

import cn.gmzc.titles.api.GrowthUnlocks;
import cn.gmzc.titles.api.TitleLevelService;
import cn.gmzc.titles.api.TitleLevelServices;
import org.bukkit.entity.Player;

final class GrowthLevelAccess {
    static final int REQUIRED_LEVEL = GrowthUnlocks.MARKET_LEVEL;

    private GrowthLevelAccess() {
    }

    static int level(Player player) {
        if (player == null) {
            return 0;
        }
        TitleLevelService service = TitleLevelServices.get();
        if (service == null) {
            return 0;
        }
        try {
            return Math.max(0, service.getLevel(player.getUniqueId()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static boolean restricted(Player player) {
        return player != null
            && level(player) < REQUIRED_LEVEL;
    }
}
