package com.inf.farlands.terrain.system.common.overworld.Oct;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 自建链的数据源：seed 派生的随机源加从 vanilla router 采集到的噪声参数。
 *
 * <p>噪声用 seed 重建而不是复用 vanilla 实例，重建后归本类所有。同一噪声名只建一次，
 * 跨 genPool 线程用并发 map 兜住。
 */
public final class OctNoiseSource {

    /** BlendedNoise 随机源的哈希名，与 vanilla 接线一致。 */
    private static final Identifier TERRAIN = Identifier.withDefaultNamespace("terrain");

    /** 默认变体主 3D 噪声的参数，取自 vanilla 的注册。 */
    private static final double BASE_XZ_SCALE = 0.25;
    private static final double BASE_Y_SCALE = 0.125;
    private static final double BASE_XZ_FACTOR = 80.0;
    private static final double BASE_Y_FACTOR = 160.0;
    private static final double BASE_SMEAR_SCALE = 8.0;

    private final OctScale scale;
    private final PositionalRandomFactory root;
    private final Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> table;
    private final Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> rebuilt = new ConcurrentHashMap<>();
    private volatile OctBlendedNoise blendedNoise;

    /** 采集表缺过键。 */
    private volatile boolean missing;

    public OctNoiseSource(OctScale scale, PositionalRandomFactory root,
            Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> table) {
        this.scale = scale;
        this.root = root;
        this.table = table;
    }

    /** 该噪声名是否采集到。采集不全时上层据此回退 vanilla router。 */
    public boolean has(ResourceKey<NormalNoise.NoiseParameters> key) {
        return table.containsKey(key);
    }

    /** 采集表里缺过键。上层据此判断构建期的异常是"采集不全"而不是别的错。 */
    public boolean missing() {
        return missing;
    }

    /** 用 seed 派生的随机源重建 NoiseHolder。缺键返回 null 并置 missing。 */
    public DensityFunction.NoiseHolder holder(ResourceKey<NormalNoise.NoiseParameters> key) {
        DensityFunction.NoiseHolder collected = table.get(key);
        if (collected == null) {
            missing = true;
            return null;
        }
        return rebuilt.computeIfAbsent(key, k -> new DensityFunction.NoiseHolder(collected.noiseData(),
                NormalNoise.create(root.fromHashOf(k.identifier()), collected.noiseData().value())));
    }

    public OctNoise noise(ResourceKey<NormalNoise.NoiseParameters> key, double xzScale, double yScale) {
        return new OctNoise(holder(key), xzScale, yScale, scale);
    }

    /** 默认变体的主 3D 噪声，只建一次。 */
    public OctBlendedNoise blendedNoise() {
        OctBlendedNoise cached = this.blendedNoise;
        if (cached == null) {
            cached = OctBlendedNoise.create(root.fromHashOf(TERRAIN), BASE_XZ_SCALE, BASE_Y_SCALE,
                    BASE_XZ_FACTOR, BASE_Y_FACTOR, BASE_SMEAR_SCALE, scale);
            this.blendedNoise = cached;
        }
        return cached;
    }
}
