package com.inf.farlands.terrain.system.terrain;

/**
 * 地形系统类型，由 Config 三维度配置指定。
 *
 * 当前只有 VOID 与 VANILLA_OVERWORLD，其余自定义系统与 theNether/theEnd 的 vanilla 包装都未
 * 移植。加回某个系统时在本枚举加值，并在 TerrainSystemRegistry 的 switch 里加分支，GenTask 与
 * 三个 filler 都不用动。
 */
public enum TerrainSystemType {
    /** 纯空气，什么都不生成。 */
    VOID,

    /** vanilla 噪声链：由 worldgen settings 派生的完整密度链，无 surface rules。 */
    VANILLA_OVERWORLD
}
