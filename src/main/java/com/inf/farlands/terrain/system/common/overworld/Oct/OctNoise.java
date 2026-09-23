package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla DensityFunctions.Noise 同式，差别是 X 与 Z 各自用自己的缩放。
 *
 * <p>vanilla 的 xzScale 由 X、Z 共用，独立缩放表达不出来；坐标先除以 scale 再乘各自的倍率，
 * scale 为 1 时与 vanilla 逐位一致。
 */
public record OctNoise(DensityFunction.NoiseHolder noise, double xzScale, double yScale, OctScale scale)
        implements DensityFunction {

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return noise.getValue(
                context.blockX() / scale.x() * xzScale,
                context.blockY() / scale.y() * yScale,
                context.blockZ() / scale.z() * xzScale);
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new OctNoise(visitor.visitNoise(noise), xzScale, yScale, scale));
    }

    @Override
    public double minValue() {
        return -maxValue();
    }

    @Override
    public double maxValue() {
        return noise.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
