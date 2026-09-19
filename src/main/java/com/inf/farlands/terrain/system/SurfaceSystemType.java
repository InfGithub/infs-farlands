package com.inf.farlands.terrain.system;

/**
 * 地表系统类型，由 Config 三维度配置指定。
 * 当前只有 VOID 与 VANILLA_OVERWORLD，旧版地表与 theNether/theEnd 的 vanilla 地表都未移植。
 */
public enum SurfaceSystemType {
    /** 不做地表处理。 */
    VOID,

    /** vanilla 主世界地表规则，即 settings.surfaceRule()。 */
    VANILLA_OVERWORLD
}
