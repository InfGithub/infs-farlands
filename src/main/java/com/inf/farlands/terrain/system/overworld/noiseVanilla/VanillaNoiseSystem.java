package com.inf.farlands.terrain.system.overworld.noiseVanilla;

import com.inf.farlands.terrain.NoiseSystem;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/** vanilla 噪声系统：finalDensity 原样放行，即 worldgen settings 派生的完整密度链。 */
public final class VanillaNoiseSystem implements NoiseSystem {

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return router.finalDensity();
    }

    @Override
    public void onLevelLoad(long seed) {
        // vanilla 密度链从 RandomState 派生，无需按 seed 预初始化
    }
}
