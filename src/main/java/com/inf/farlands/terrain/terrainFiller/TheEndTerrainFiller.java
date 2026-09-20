package com.inf.farlands.terrain.terrainFiller;

import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.system.terrain.TerrainSystemRegistry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * 末地地形填充器：维度系统 = Config.endTerrainSystem。逻辑全部继承
 * {@link AbstractTerrainFiller}；biome 阶段由 BiomeFiller 独立负责，fill 不再管 biome。
 */
public final class TheEndTerrainFiller extends AbstractTerrainFiller {

    private TheEndTerrainFiller(NoiseGeneratorSettings settings) {
        super(settings);
    }

    /** 从 generator 构造，由 GenQueue 惰性缓存持有。 */
    public static TheEndTerrainFiller of(ServerLevel level) {
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        return new TheEndTerrainFiller(gen.generatorSettings().value());
    }

    @Override
    protected TerrainFillerContext.TerrainDimension dimension() {
        return TerrainFillerContext.TerrainDimension.END;
    }

    @Override
    protected TerrainSystem terrainSystem() {
        return TerrainSystemRegistry.getTheEnd();
    }
}
