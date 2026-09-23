package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla DensityFunctions.FindTopSurface 同式，差别是 lowerBound 是 double。
 *
 * <p>扫描本身按整格 Y 步进，下限由调用方按 scaleY 传进来，浮点只用于表述缩放后的下界。
 */
public record OctFindTopSurface(DensityFunction density, DensityFunction upperBound, double lowerBound,
        int cellHeight) implements DensityFunction {

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int topY = Mth.floor(this.upperBound.compute(context) / this.cellHeight) * this.cellHeight;
        if (topY <= this.lowerBound) {
            return this.lowerBound;
        }

        for (int blockY = topY; blockY >= this.lowerBound; blockY -= this.cellHeight) {
            if (this.density.compute(new DensityFunction.SinglePointContext(context.blockX(), blockY, context.blockZ())) > 0.0) {
                return blockY;
            }
        }

        return this.lowerBound;
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new OctFindTopSurface(this.density.mapAll(visitor), this.upperBound.mapAll(visitor),
                this.lowerBound, this.cellHeight));
    }

    @Override
    public double minValue() {
        return this.lowerBound;
    }

    @Override
    public double maxValue() {
        return Math.max(this.lowerBound, this.upperBound.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
