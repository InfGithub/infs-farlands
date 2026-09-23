package com.inf.farlands.terrain.system.common.overworld.Oct;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 从 vanilla router 采集噪声参数 holder，供自建链重建 NormalNoise。
 *
 * <p>必须遍历整条 router 而不能只看 finalDensity：矿脉与气候的噪声不在 finalDensity 树里。
 * NoiseRouter.mapAll 会走全部 15 个字段，一次采齐。
 */
public final class OctNoiseHarvest {

    private OctNoiseHarvest() {
    }

    /**
     * 采集噪声名到 NoiseHolder 的映射。树的 provider 是注册表 holder，名字取得到；
     * 取不到名字的条目直接跳过。
     */
    public static Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> harvest(
            NoiseRouter router) {
        Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> out = new HashMap<>();
        router.mapAll(new DensityFunction.Visitor() {

            @Override
            public DensityFunction apply(DensityFunction input) {
                return input;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                noise.noiseData().unwrapKey().ifPresent(key -> out.putIfAbsent(key, noise));
                return noise;
            }
        });
        return out;
    }
}
