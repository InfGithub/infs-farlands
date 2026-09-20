package com.inf.farlands.terrain.system.carver;

/**
 * 雕刻系统类型，由 Config 三维度配置指定。
 * 当前只有 VOID 与 VANILLA_OVERWORLD，自研雕刻与 theNether/theEnd 的 vanilla 雕刻都未移植。
 */
public enum CarverSystemType {
    /** 不雕刻。 */
    VOID,

    /** vanilla 主世界雕刻：biome json 的 CARVERS，CAVE/CAVE_EXTRA_UNDERGROUND/CANYON。 */
    VANILLA_OVERWORLD
}
