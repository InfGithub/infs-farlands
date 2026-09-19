package com.inf.farlands.terrain;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 逐块几何地形系统，fillBlock 每格直接判定，绕过密度链。
 *
 * fillBlock 恒非 null 时密度链不执行，因此本类没有 createFinalDensity，
 * NoiseChunk 构造用的 finalDensity 由调用方给占位。
 */
public interface BlockSystem extends TerrainSystem {

    /** 块级判定，返回 AIR 表示空气，返回方块表示实。 */
    BlockState fillBlock(int x, int y, int z);

    /**
     * 空气 aquifer，覆盖基接口的 null 默认。fillBlock 短路下密度链不参与，
     * vanilla aquifer 构造只浪费，恒 AIR 且非 null 可让 MaterialRuleList 短路。
     */
    @Override
    default Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
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
