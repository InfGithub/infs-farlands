package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla DensityFunctions.ShiftedNoise 同式，差别是 X 与 Z 各自缩放，且三个 shift 子函数照常下钻。
 */
public record OctShiftedNoise(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
        double xzScale, double yScale, DensityFunction.NoiseHolder noise, OctScale scale)
        implements DensityFunction {

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double x = context.blockX() / scale.x() * xzScale + shiftX.compute(context);
        double y = context.blockY() / scale.y() * yScale + shiftY.compute(context);
        double z = context.blockZ() / scale.z() * xzScale + shiftZ.compute(context);
        return noise.getValue(x, y, z);
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new OctShiftedNoise(
                shiftX.mapAll(visitor), shiftY.mapAll(visitor), shiftZ.mapAll(visitor),
                xzScale, yScale, visitor.visitNoise(noise), scale));
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
