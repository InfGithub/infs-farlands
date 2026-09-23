package com.inf.farlands.terrain.system.terrain.noise.misc.Hex;

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

/** Hex 噪声系统：XZ 正弦高度场，不生成水。 */
public final class HexNoiseSystem implements NoiseSystem {

    /** 高度场基高，单位方块。 */
    private static final double BASE = 64.0;

    /** 高度场振幅，单位方块。 */
    private static final double AMPLITUDE = 16.0;

    /** 正弦波长，单位方块，X 与 Z 共用。 */
    private static final double WAVELENGTH = 64.0;

    private final double scaleX;
    private final double scaleY;
    private final double scaleZ;

    public HexNoiseSystem() {
        this(1.0, 1.0, 1.0);
    }

    public HexNoiseSystem(double scaleX, double scaleY, double scaleZ) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        PointFunction pointFunction = new ScaledPointInput(
                HexNoiseSystem::sineWave, this.scaleX, this.scaleY, this.scaleZ);
        // y 项为 y/scaleY，密度区间随 |scaleY| 收窄。
        double ySpan = FarlandsConstant.MAX_BLOCK / Math.abs(this.scaleY);
        return new SineWaveDensity(pointFunction, BASE - AMPLITUDE - ySpan, BASE + AMPLITUDE + ySpan);
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
     * 纯公式 f(x, y, z) = BASE + AMPLITUDE·sin(2πx/波长)·sin(2πz/波长) − y。
     * 输入是实数坐标，不含任何缩放。
     */
    private static double sineWave(double x, double y, double z) {
        double phaseX = x * (Math.PI * 2.0 / WAVELENGTH);
        double phaseZ = z * (Math.PI * 2.0 / WAVELENGTH);
        return BASE + AMPLITUDE * Math.sin(phaseX) * Math.sin(phaseZ) - y;
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
    private record SineWaveDensity(PointFunction pointFunction, double boundMin, double boundMax)
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
