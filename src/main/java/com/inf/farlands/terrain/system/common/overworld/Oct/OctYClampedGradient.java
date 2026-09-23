package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla YClampedGradient 同式，但 Y 边界是 double。
 *
 * <p>vanilla 的边界是 int，浮点 scaleY 会被取整；这里自建一份以保留浮点。两侧钳制区语义不变。
 */
public record OctYClampedGradient(double fromY, double toY, double fromValue, double toValue)
        implements DensityFunction.SimpleFunction {

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return Mth.clampedMap(context.blockY(), fromY, toY, fromValue, toValue);
    }

    @Override
    public double minValue() {
        return Math.min(fromValue, toValue);
    }

    @Override
    public double maxValue() {
        return Math.max(fromValue, toValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
