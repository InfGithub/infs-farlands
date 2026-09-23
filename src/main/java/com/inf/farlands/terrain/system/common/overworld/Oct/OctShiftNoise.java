package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla DensityFunctions.ShiftNoise 同族，差别是坐标先按轴除以 scale。
 *
 * <p>vanilla 的三个 compute 把 0.25 写死在内部且不做缩放，不跟着缩就会与整体缩放脱节。
 * 三个取值点与 vanilla 逐字对应，scale 为 1 时与 vanilla 逐位一致。
 */
public interface OctShiftNoise extends DensityFunction {

    DensityFunction.NoiseHolder offsetNoise();

    OctScale scale();

    /** vanilla ShiftNoise.compute 的直译，入参是已按轴缩放过的局部坐标。 */
    default double shifted(double localX, double localY, double localZ) {
        return offsetNoise().getValue(localX * 0.25, localY * 0.25, localZ * 0.25) * 4.0;
    }

    @Override
    default double minValue() {
        return -maxValue();
    }

    @Override
    default double maxValue() {
        return offsetNoise().maxValue() * 4.0;
    }

    @Override
    default void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    /** 与 vanilla Shift 同形，读 x、y、z。 */
    record Shift(DensityFunction.NoiseHolder offsetNoise, OctScale scale) implements OctShiftNoise {

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return shifted(context.blockX() / scale.x(), context.blockY() / scale.y(), context.blockZ() / scale.z());
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new Shift(visitor.visitNoise(offsetNoise), scale));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(MapCodec.unit(this));
        }
    }

    /** 与 vanilla ShiftA 同形，读 x、z，y 固定 0。 */
    record ShiftA(DensityFunction.NoiseHolder offsetNoise, OctScale scale) implements OctShiftNoise {

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return shifted(context.blockX() / scale.x(), 0.0, context.blockZ() / scale.z());
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new ShiftA(visitor.visitNoise(offsetNoise), scale));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(MapCodec.unit(this));
        }
    }

    /** 与 vanilla ShiftB 同形，z 与 x 互换，y 固定 0。 */
    record ShiftB(DensityFunction.NoiseHolder offsetNoise, OctScale scale) implements OctShiftNoise {

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return shifted(context.blockZ() / scale.z(), context.blockX() / scale.x(), 0.0);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new ShiftB(visitor.visitNoise(offsetNoise), scale));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return KeyDispatchDataCodec.of(MapCodec.unit(this));
        }
    }
}
