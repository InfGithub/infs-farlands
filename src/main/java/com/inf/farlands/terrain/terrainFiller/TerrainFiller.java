package com.inf.farlands.terrain.terrainFiller;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 维度地形填充器接口，按维度填充一段 section。
 *
 * 1 段 section 对应 1 个 NoiseChunk。GenQueue 按 level.dimension() 分派并惰性缓存实现。
 */
public interface TerrainFiller {

    /** 填一段 section。调用方保证同 chunk 串行。 */
    void fill(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY);
}
