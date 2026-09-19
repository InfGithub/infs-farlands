package com.inf.farlands.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 群系系统，决定 section 的 biome 数据来源。
 *
 * 与地形系统正交，biome 是独立阶段，vanilla 的 BIOMES 先于 NOISE。
 * 由 BiomeSystemRegistry 按维度配置选择并持有单例，当前只有 VOID。
 * 实现必须无状态且纯方法，fillBiomes 跑 genPool 多线程，实例被多个 fill 任务共享。
 */
public interface BiomeSystem {

    /** 填 [minSectionY, maxSectionY] 内每个 section 的 4x4x4 biome 网格。 */
    void fillBiomes(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY);
}
