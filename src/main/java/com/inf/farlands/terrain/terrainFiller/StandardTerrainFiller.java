package com.inf.farlands.terrain.terrainFiller;

import com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173.BetaContext;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 地形填充器的唯一实现。逻辑全在 {@link AbstractTerrainFiller}：fill 从 level 现取该维度的
 * TerrainSystem 与 generatorSettings，所以维度不参与分派，任何维度都走这一份。
 *
 * <p>此前有按维度分出的三个子类，但三者实现体全空，维度不参与任何判定，那层区分是假象。
 * 真需要按维度换填充器时，再在 {@code LevelSystems.terrainFiller()} 的构造点按维度取。
 *
 * <p>fill 入口装 biome 查询侧信道、出口清：密度函数只拿得到坐标，需要按列查 biome 的系统从
 * 这里取 sampler 与 biomeSource。未选中这类系统时，装上的侧信道无人读取。
 */
public final class StandardTerrainFiller extends AbstractTerrainFiller {

    public StandardTerrainFiller() {
    }

    @Override
    protected void setContext(RandomState randomState, ServerLevel level) {
        BetaContext.set(randomState.sampler(), level.getChunkSource().getGenerator().getBiomeSource());
    }

    @Override
    protected void clearContext() {
        BetaContext.clear();
    }
}
