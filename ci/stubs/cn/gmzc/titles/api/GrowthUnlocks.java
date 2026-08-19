package cn.gmzc.titles.api;

/**
 * 云端编译用 ABI 桩：常量故意不加 final，强制编译为运行期字段读取，
 * 运行时读取真实 GMZCTitles 插件中的数值。
 */
public final class GrowthUnlocks {
    public static int TEAM_LEVEL;
    public static int MARKET_LEVEL;

    private GrowthUnlocks() {
    }
}
