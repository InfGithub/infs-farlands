package com.inf.farlands.terrain;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 地形系统基接口，两类互斥的填充范式共享的部分，生命周期回调加流体与 aquifer 提供。
 *
 * 两个互斥子接口，一个实现类只实现其一，分派点 instanceof BlockSystem 优先：
 * NoiseSystem 走密度链，BlockSystem 走逐块几何。
 *
 * 线程模型：onLevelLoad 在主线程世界加载时调用；createFluidPicker 与 createAquifer 在
 * NoiseChunk 构造时调用，跑 genPool 或主线程。onChunkFillStart 当前无调用方，默认空实现。
 */
public interface TerrainSystem {

    /**
     * 管线 fill 的 fluidPicker。null 表示用管线默认，即 y &lt; -54 为 lava，其余为 seaLevel 流体。
     * 全空气系统必须覆盖为空气 picker，否则 density 恒负时 aquifer 会按 fluidPicker 填出全水世界。
     */
    default Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        return null;
    }

    /**
     * 管线 fill 的 aquifer。null 表示用 vanilla NoiseBasedAquifer。
     * 全空气系统覆盖为恒 AIR 且非 null，使 MaterialRuleList 短路，矿脉规则不执行。
     */
    default Aquifer createAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        return null;
    }

    /** 主世界加载时按 seed 初始化系统噪声。 */
    void onLevelLoad(long seed);

    /** 每次 fill 开始时的 chunk 上下文回调。当前无调用方。 */
    default void onChunkFillStart(ChunkAccess chunk) {
    }
}
