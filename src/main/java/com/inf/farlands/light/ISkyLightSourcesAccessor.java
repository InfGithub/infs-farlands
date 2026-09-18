package com.inf.farlands.light;

/**
 * ChunkSkyLightSources 的 fillFrom 完成标志 accessor，由 Mixin 的 @Unique 字段承载。
 *
 * 播种读邻居时需区分三种状态：fill 中即未 fillFrom，将有方块，方向位保守，等它
 * fill 完重播修正；空气即 fillFrom 完成但无源，穿透；有源即 fillFrom 完成且有
 * 列顶，正常方向位。内容判断 getHighestLowestSourceY == MIN_VALUE 无法区分
 * "fill 中"与"空气"，因为 fillFrom 后空气也无源 必须用显式标志。
 */
public interface ISkyLightSourcesAccessor {
    boolean farlands$isFillFromDone();
}
