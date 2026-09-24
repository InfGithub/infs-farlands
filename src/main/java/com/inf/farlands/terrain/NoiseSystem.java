package com.inf.farlands.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * 密度链地形系统，决定地形密度函数来源。
 *
 * 由 ServerLevel 构造末尾按 id 在 SystemRegistries 里新建，实例随 level 走。
 * createFinalDensity 在 NoiseChunk 构造时调用，跑 genPool 或主线程，
 * 实现必须返回新实例，DensityFunction 带状态缓存，禁止共享。
 */
public interface NoiseSystem extends TerrainSystem {

    /** 返回该系统的 finalDensity，vanilla 链或自研密度函数。 */
    DensityFunction createFinalDensity(NoiseRouter router);

    /**
     * 返回该系统的整条噪声路由。默认只把 finalDensity 换成本系统的，其余字段照抄 vanilla；
     * 需要控制 preliminarySurfaceLevel、矿脉门等字段的系统覆写本方法。
     */
    default NoiseRouter createRouter(NoiseRouter vanilla) {
        return new NoiseRouter(
                vanilla.barrierNoise(),
                vanilla.fluidLevelFloodednessNoise(),
                vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(),
                vanilla.temperature(),
                vanilla.vegetation(),
                vanilla.continents(),
                vanilla.erosion(),
                vanilla.depth(),
                vanilla.ridges(),
                vanilla.preliminarySurfaceLevel(),
                createFinalDensity(vanilla),
                vanilla.veinToggle(),
                vanilla.veinRidged(),
                vanilla.veinGap());
    }

    /**
     * 自定义 cell 网格，单位是 noiseSize，乘 4 得 cell 格数。
     * 返回 {horizontal, vertical}，null 表示用维度 settings 默认。
     */
    default int[] noiseSize() {
        return null;
    }
}
