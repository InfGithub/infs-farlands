package com.inf.farlands.terrain.system.common.overworld.Oct;

import java.util.stream.IntStream;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * 与 vanilla BlendedNoise 同式，差别是 X 与 Z 各用一个乘数。
 *
 * <p>
 * vanilla 的 xzMultiplier 由 X、Z 共用，独立缩放表达不出来，所以整份 compute 照抄后拆成两个乘数。
 * scale 为 1 时与 vanilla 逐位一致。三个 PerlinNoise 必须由同一个 RandomSource 依次创建，
 * 与 vanilla 构造器一致，否则噪声不同源。
 */
public final class OctBlendedNoise implements DensityFunction.SimpleFunction {

    /** 与 vanilla 同：乘数基值。 */
    private static final double MULTIPLIER_BASE = 684.412;

    private final PerlinNoise minLimitNoise;
    private final PerlinNoise maxLimitNoise;
    private final PerlinNoise mainNoise;
    private final double xzMultiplierX;
    private final double xzMultiplierZ;
    private final double yMultiplier;
    private final double xzFactor;
    private final double yFactor;
    private final double smearScaleMultiplier;
    private final double maxValue;

    private OctBlendedNoise(PerlinNoise minLimitNoise, PerlinNoise maxLimitNoise, PerlinNoise mainNoise,
            double xzScale, double yScale, double xzFactor, double yFactor, double smearScaleMultiplier,
            OctScale scale) {
        this.minLimitNoise = minLimitNoise;
        this.maxLimitNoise = maxLimitNoise;
        this.mainNoise = mainNoise;
        this.xzMultiplierX = MULTIPLIER_BASE * xzScale / scale.x();
        this.xzMultiplierZ = MULTIPLIER_BASE * xzScale / scale.z();
        this.yMultiplier = MULTIPLIER_BASE * yScale / scale.y();
        this.xzFactor = xzFactor;
        this.yFactor = yFactor;
        this.smearScaleMultiplier = smearScaleMultiplier;
        this.maxValue = minLimitNoise.maxBrokenValue(this.yMultiplier);
    }

    /**
     * 按 vanilla 的八度范围建三个 PerlinNoise：min/max 是 -15..0，main 是 -7..0。
     * random 三次依次传入同一个实例，消费同一随机流，与 vanilla 构造器同序。
     */
    @SuppressWarnings("deprecation")
    public static OctBlendedNoise create(RandomSource random, double xzScale, double yScale,
            double xzFactor, double yFactor, double smearScaleMultiplier, OctScale scale) {
        return new OctBlendedNoise(
                PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-15, 0)),
                PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-15, 0)),
                PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-7, 0)),
                xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier, scale);
    }

    @SuppressWarnings("deprecation")
    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double limitX = context.blockX() * this.xzMultiplierX;
        double limitY = context.blockY() * this.yMultiplier;
        double limitZ = context.blockZ() * this.xzMultiplierZ;
        double mainX = limitX / this.xzFactor;
        double mainY = limitY / this.yFactor;
        double mainZ = limitZ / this.xzFactor;
        double limitSmear = this.yMultiplier * this.smearScaleMultiplier;
        double mainSmear = limitSmear / this.yFactor;
        double blendMin = 0.0;
        double blendMax = 0.0;
        double mainNoiseValue = 0.0;
        double pow = 1.0;

        for (int i = 0; i < 8; i++) {
            ImprovedNoise noise = this.mainNoise.getOctaveNoise(i);
            if (noise != null) {
                mainNoiseValue += noise.noise(PerlinNoise.wrap(mainX * pow), PerlinNoise.wrap(mainY * pow),
                        PerlinNoise.wrap(mainZ * pow), mainSmear * pow, mainY * pow) / pow;
            }
            pow /= 2.0;
        }

        double factor = (mainNoiseValue / 10.0 + 1.0) / 2.0;
        boolean isMax = factor >= 1.0;
        boolean isMin = factor <= 0.0;
        pow = 1.0;

        for (int i = 0; i < 16; i++) {
            double wx = PerlinNoise.wrap(limitX * pow);
            double wy = PerlinNoise.wrap(limitY * pow);
            double wz = PerlinNoise.wrap(limitZ * pow);
            double yScalePow = limitSmear * pow;
            if (!isMax) {
                ImprovedNoise minNoise = this.minLimitNoise.getOctaveNoise(i);
                if (minNoise != null) {
                    blendMin += minNoise.noise(wx, wy, wz, yScalePow, limitY * pow) / pow;
                }
            }
            if (!isMin) {
                ImprovedNoise maxNoise = this.maxLimitNoise.getOctaveNoise(i);
                if (maxNoise != null) {
                    blendMax += maxNoise.noise(wx, wy, wz, yScalePow, limitY * pow) / pow;
                }
            }
            pow /= 2.0;
        }

        return Mth.clampedLerp(factor, blendMin / 512.0, blendMax / 512.0) / 128.0;
    }

    @Override
    public double minValue() {
        return -this.maxValue;
    }

    @Override
    public double maxValue() {
        return this.maxValue;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
