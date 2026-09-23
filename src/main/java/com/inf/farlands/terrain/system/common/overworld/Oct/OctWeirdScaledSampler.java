package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.mojang.serialization.MapCodec;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 与 vanilla DensityFunctions.WeirdScaledSampler 同式，差别是除以 rarity 的坐标再各轴除以 scale。
 *
 * <p>vanilla 用同一个 rarity 除三轴，所以只有三轴同倍率时才等价于统一缩放；这里按轴分开，
 * scale 为 1 时与 vanilla 逐位一致。
 */
public record OctWeirdScaledSampler(DensityFunction input, DensityFunction.NoiseHolder noise,
        RarityValueMapper rarityValueMapper, OctScale scale) implements DensityFunction {

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double rarity = rarityValueMapper.mapper(input.compute(context));
        return rarity * Math.abs(noise.getValue(
                context.blockX() / (rarity * scale.x()),
                context.blockY() / (rarity * scale.y()),
                context.blockZ() / (rarity * scale.z())));
    }

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new OctWeirdScaledSampler(input.mapAll(visitor), visitor.visitNoise(noise),
                rarityValueMapper, scale));
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return rarityValueMapper.maxRarity * noise.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // 运行时注入的节点，不参与序列化，unit 只是满足接口。
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }

    /**
     * 与 vanilla 同取值同映射，两个映射函数从 NoiseRouterData 的受保护内部类抄来，是纯数学。
     */
    public enum RarityValueMapper {
        TYPE1(2.0),
        TYPE2(3.0);

        private final double maxRarity;

        RarityValueMapper(double maxRarity) {
            this.maxRarity = maxRarity;
        }

        public double mapper(double value) {
            return this == TYPE1 ? rarity3D(value) : rarity2D(value);
        }

        private static double rarity2D(double value) {
            if (value < -0.75) {
                return 0.5;
            }
            if (value < -0.5) {
                return 0.75;
            }
            if (value < 0.5) {
                return 1.0;
            }
            return value < 0.75 ? 2.0 : 3.0;
        }

        private static double rarity3D(double value) {
            if (value < -0.5) {
                return 0.75;
            }
            if (value < 0.0) {
                return 1.0;
            }
            return value < 0.5 ? 1.5 : 2.0;
        }
    }
}
