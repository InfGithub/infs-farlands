package com.inf.farlands.terrain.system.terrain.noise.misc.Weierstrass;

import com.mojang.serialization.MapCodec;
import com.inf.farlands.FarlandsConstant;
import com.inf.farlands.terrain.NoiseSystem;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** Weierstrass 噪声系统：XZ 自相似高度场，不生成水。 */
public final class WeierstrassNoiseSystem implements NoiseSystem {

    /** 高度场基高，单位方块。 */
    private static final double BASE = 64.0;

    /** 高度场振幅，单位方块。 */
    private static final double AMPLITUDE = 16.0;

    /** 最粗一层的波长，单位方块，X 与 Z 共用。 */
    private static final double WAVELENGTH = 64.0;

    /** 每往一层频率乘这个数，即波长除以它。 */
    private static final double LACUNARITY = 3.0;

    /** 每往一层振幅乘这个数。与 LACUNARITY 相乘须大于 1，否则各尺度斜率相同，出不来自相似粗糙度。 */
    private static final double GAIN = 0.5;

    /**
     * 层数。波长每层除以 LACUNARITY、振幅乘 GAIN。波长掉到方块尺度以下之后，多出来的层不再产出可
     * 见的形状，只给表面加逐格抖动，振幅按 GAIN 的幂衰减；层数每加一层，每格每轴多一次 sin。
     */
    private static final int OCTAVES = 20;

    /** 归一化因子，把各层振幅和压回 1，使 W 的值域落在 [-1, 1]。 */
    private static final double NORMALIZATION = (1.0 - GAIN) / (1.0 - Math.pow(GAIN, OCTAVES));

    private final double scaleX;
    private final double scaleY;
    private final double scaleZ;

    public WeierstrassNoiseSystem() {
        this(1.0, 1.0, 1.0);
    }

    public WeierstrassNoiseSystem(double scaleX, double scaleY, double scaleZ) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        PointFunction pointFunction = new ScaledPointInput(
                WeierstrassNoiseSystem::heightField, this.scaleX, this.scaleY, this.scaleZ);
        // y 项为 y/scaleY，密度区间随 |scaleY| 收窄。
        double ySpan = FarlandsConstant.MAX_BLOCK / Math.abs(this.scaleY);
        return new PointFunctionDensity(pointFunction, BASE - AMPLITUDE - ySpan, BASE + AMPLITUDE + ySpan);
    }

    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        // FluidStatus.at(y) 是 y < fluidLevel 取流体，否则 AIR。fluidLevel 取 MIN_VALUE 恒出 AIR。
        return (x, y, z) -> new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    @Override
    public Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        // 密度为正返回 null，交给后续规则与默认方块；非正才由这里接管成空气，因此不生成水。
        return new Aquifer() {
            @Override
            public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
                return substance > 0.0 ? null : Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }

    /**
     * 纯公式，输入是实数坐标，不含缩放：h = BASE + AMPLITUDE·W(x)·W(z) − y。
     * 两个方向各取一条 Weierstrass 和的积，每一层都是肋乘肋，自相似沿两轴同时嵌套。
     */
    private static double heightField(double x, double y, double z) {
        return BASE + AMPLITUDE * weierstrass(x) * weierstrass(z) - y;
    }

    /** 一维 Weierstrass 和，值域 [-1, 1]，每往上一层波长除以 LACUNARITY、振幅乘 GAIN。 */
    private static double weierstrass(double t) {
        double sum = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0 / WAVELENGTH;
        for (int n = 0; n < OCTAVES; n++) {
            sum += amplitude * Math.sin(Math.PI * 2.0 * t * frequency);
            amplitude *= GAIN;
            frequency *= LACUNARITY;
        }
        return sum * NORMALIZATION;
    }

    /** 三维实数点上的标量函数。 */
    @FunctionalInterface
    private interface PointFunction {
        double apply(double x, double y, double z);
    }

    /** 缩放包装：只对输入点做三轴缩放，再交给被包的函数，公式本身不动。 */
    private record ScaledPointInput(PointFunction inner, double scaleX, double scaleY, double scaleZ)
            implements PointFunction {

        @Override
        public double apply(double x, double y, double z) {
            return this.inner.apply(x / this.scaleX, y / this.scaleY, z / this.scaleZ);
        }
    }

    /** 节点只把 int 坐标拓宽成 double 再委托，缩放与公式都在被委托的一侧。 */
    private record PointFunctionDensity(PointFunction pointFunction, double boundMin, double boundMax)
            implements DensityFunction.SimpleFunction {

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return this.pointFunction.apply(context.blockX(), context.blockY(), context.blockZ());
        }

        @Override
        public double minValue() {
            return this.boundMin;
        }

        @Override
        public double maxValue() {
            return this.boundMax;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            // 运行时注入的节点，不参与序列化，unit 只是满足接口。
            return KeyDispatchDataCodec.of(MapCodec.unit(this));
        }
    }
}
