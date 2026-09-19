package com.inf.farlands.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * 密度链地形系统，决定地形密度函数来源。
 *
 * 由 NoiseSystemRegistry 按维度配置选择并持有单例，当前只有 VOID。
 * createFinalDensity 在 NoiseChunk 构造时调用，跑 genPool 或主线程，
 * 实现必须返回新实例，DensityFunction 带状态缓存，禁止共享。
 */
public interface NoiseSystem extends TerrainSystem {

    /** 返回该系统的 finalDensity，vanilla 链或自研密度函数。 */
    DensityFunction createFinalDensity(NoiseRouter router);

    /**
     * 自定义 cell 网格，单位是 noiseSize，乘 4 得 cell 格数。
     * 返回 {horizontal, vertical}，null 表示用维度 settings 默认。
     */
    default int[] noiseSize() {
        return null;
    }
}
