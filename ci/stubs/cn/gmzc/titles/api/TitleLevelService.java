package cn.gmzc.titles.api;

import java.util.UUID;

/** 云端编译用 ABI 桩。 */
public interface TitleLevelService {
    int getLevel(UUID playerId);
}
