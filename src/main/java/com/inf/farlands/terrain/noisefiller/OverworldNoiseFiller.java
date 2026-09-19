package com.inf.farlands.terrain.noisefiller;

import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.system.NoiseSystemRegistry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * 主世界地形填充器：维度系统 = Config.overworldTerrainSystem。逻辑全部继承
 * {@link AbstractNoiseFiller}；biome 阶段由 BiomeFiller 独立负责，fill 不再管 biome。
 */
public final class OverworldNoiseFiller extends AbstractNoiseFiller {

    private OverworldNoiseFiller(NoiseGeneratorSettings settings) {
        super(settings);
    }

    /** 从 generator 构造，由 GenQueue 惰性缓存持有。 */
    public static OverworldNoiseFiller of(ServerLevel level) {
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        return new OverworldNoiseFiller(gen.generatorSettings().value());
    }

    @Override
    protected NoiseFillerContext.TerrainDimension dimension() {
        return NoiseFillerContext.TerrainDimension.OVERWORLD;
    }

    @Override
    protected TerrainSystem terrainSystem() {
        return NoiseSystemRegistry.getOverworld();
    }
}
