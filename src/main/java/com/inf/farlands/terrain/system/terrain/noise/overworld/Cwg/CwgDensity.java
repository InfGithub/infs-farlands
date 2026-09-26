package com.inf.farlands.terrain.system.terrain.noise.overworld.Cwg;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 冻结八度组成的密度链。
 *
 * <p>算子顺序是契约，颠倒会改变地形：selector 决定 low 与 high 之间的插值位置，depth 那一路
 * 先钳到 [-2,1] 再按正负分两段缩放，最后整体乘 volatility、加 height、减 sign(volatility) 乘 y。
 * depth 的缩放必须先钳后分，钳在分之后会让干燥侧的系数落到湿润侧。
 *
 * <p>height 与 volatility 取自两条二维样条，竖直方向的取值不参与，所以同一列上两者相同。
 * volatility 取绝对值，负号会让减 y 的那一项反过来。
 *
 * <p>无状态：实例被多个生成任务共享，字段全部 final，compute 只读。minValue 与 maxValue 取
 * 正负 1e300 而不是 Double 的上界，因为上层会把密度与其它项相加，用上界会在求和处溢出成无穷。
 */
public final class CwgDensity implements DensityFunction.SimpleFunction {

    /** 密度链的算子参数，按键声明处一次读出。 */
    public record Ops(
            double selectorFactor,
            double selectorOffset,
            double lowFactor,
            double lowOffset,
            double highFactor,
            double highOffset,
            double depthFactor,
            double depthOffset,
            double heightFactor,
            double heightOffset,
            double heightVariationFactor,
            double heightVariationOffset,
            double specialHeightVariationFactor) {
    }

    private final CwgNoise selector;
    private final CwgNoise low;
    private final CwgNoise high;
    private final CwgNoise depth;
    private final DensityFunction offsetSpline;
    private final DensityFunction factorSpline;
    private final Ops ops;

    public CwgDensity(CwgNoise selector, CwgNoise low, CwgNoise high, CwgNoise depth,
            DensityFunction offsetSpline, DensityFunction factorSpline, Ops ops) {
        this.selector = selector;
        this.low = low;
        this.high = high;
        this.depth = depth;
        this.offsetSpline = offsetSpline;
        this.factorSpline = factorSpline;
        this.ops = ops;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double x = context.blockX();
        double y = context.blockY();
        double z = context.blockZ();

        double selectorValue = this.selector.normalized(x, y, z) * this.ops.selectorFactor()
                + this.ops.selectorOffset();
        selectorValue = selectorValue < 0.0 ? 0.0 : (selectorValue > 1.0 ? 1.0 : selectorValue);

        double lowValue = this.low.normalized(x, y, z) * this.ops.lowFactor() + this.ops.lowOffset();
        double highValue = this.high.normalized(x, y, z) * this.ops.highFactor() + this.ops.highOffset();

        double depthValue = this.depth.normalized(x, y, z) * this.ops.depthFactor() + this.ops.depthOffset();
        if (depthValue < 0.0) {
            depthValue *= -0.3;
        }
        depthValue = depthValue * 3.0 - 2.0;
        depthValue = depthValue < -2.0 ? -2.0 : (depthValue > 1.0 ? 1.0 : depthValue);
        if (depthValue < 0.0) {
            depthValue /= 2.0 * 2.0 * 1.4;
        } else if (depthValue > 0.0) {
            depthValue /= 8.0;
        }
        depthValue *= 0.2 * 17.0 / 64.0;

        double height = this.offsetSpline.compute(context) * this.ops.heightFactor()
                + this.ops.heightOffset();
        double variation = Math.abs(this.factorSpline.compute(context));
        if (height > y) {
            variation *= this.ops.specialHeightVariationFactor();
        }
        double volatility = variation * this.ops.heightVariationFactor() + this.ops.heightVariationOffset();

        double blended = lowValue + selectorValue * (highValue - lowValue);
        return (blended + depthValue) * volatility + height - Math.signum(volatility) * y;
    }

    @Override
    public double minValue() {
        return -1.0E300;
    }

    @Override
    public double maxValue() {
        return 1.0E300;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
