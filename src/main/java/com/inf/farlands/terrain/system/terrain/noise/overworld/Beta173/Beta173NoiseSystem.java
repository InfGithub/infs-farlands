package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * beta 1.7.3 噪声系统，把 {@link BetaDensityFunction} 注入 finalDensity。
 *
 * <p>参数两项：topFadeEnabled 是地形密度公式的顶部渐消项，默认关；seed 决定噪声实例，默认每次
 * 随机，取值落盘后随世界固定。
 *
 * <p>噪声实例由本系统持有，随 level 走。每次 createFinalDensity 返回新的密度函数，因为密度函数
 * 带 8 角与网格点缓存，禁止共享。
 */
public final class Beta173NoiseSystem implements NoiseSystem {

    /** 声明：顶部渐消默认关；seed 只有一条映射，左端是空串，求值一次得到一个随机 long。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofBoolean("topFadeEnabled").define(false).build(),
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build());

    private final boolean topFadeEnabled;
    private final BetaTerrainNoise noise;

    public Beta173NoiseSystem(SystemArgs args) {
        this.topFadeEnabled = args.getBoolean("topFadeEnabled");
        this.noise = new BetaTerrainNoise(args.getLong("seed"));
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return new BetaDensityFunction(this.noise, this.topFadeEnabled);
    }
}
