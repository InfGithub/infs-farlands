package com.inf.farlands.terrain.system.terrain.noise.overworld.Oct;

import com.inf.farlands.terrain.NoiseSystem;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/** vanilla 噪声系统：finalDensity 原样放行，即 worldgen settings 派生的完整密度链。 */
public final class OctNoiseSystem implements NoiseSystem {

    private final double scaleX;
    private final double scaleY;
    private final double scaleZ;

    public OctNoiseSystem() {
        this(1.0, 1.0, 1.0);
    }

    public OctNoiseSystem(double scaleX, double scaleY, double scaleZ) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return router.finalDensity();
    }
}
