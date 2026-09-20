package com.inf.farlands.terrain.system.biome;

/**
 * 群系系统类型，由 Config 三维度配置指定。
 * 当前只有 VOID 与 VANILLA_OVERWORLD，其余未移植。
 */
public enum BiomeSystemType {
    /** 虚空群系，整个世界的 biome 恒为 the_void。 */
    VOID,

    /** vanilla 主世界群系：MultiNoiseBiomeSource 原版布局。 */
    VANILLA_OVERWORLD
}
