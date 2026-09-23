package com.inf.farlands.terrain.system.terrain.noise.misc.Void;

import com.inf.farlands.terrain.NoiseSystem;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** VOID 噪声系统：纯空气，什么都不生成。 */
public final class VoidNoiseSystem implements NoiseSystem {

    public VoidNoiseSystem() {
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        // 恒负。aquifer 在 density <= 0 时走流体路径，配合空气 fluidPicker 得全空气。
        return DensityFunctions.constant(-1.0);
    }

    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        // FluidStatus.at(y) 是 y < fluidLevel 取流体，否则 AIR。fluidLevel 取 MIN_VALUE 恒出 AIR。
        return (x, y, z) -> new Aquifer.FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
    }

    @Override
    public Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        // 恒 AIR 且非 null 使 MaterialRuleList 短路，矿脉规则不执行，得全空气且 O(1)。
        return new Aquifer() {
            @Override
            public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
                return Blocks.AIR.defaultBlockState();
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }
}
