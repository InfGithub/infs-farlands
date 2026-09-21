package com.inf.farlands.terrain.terrainFiller;

/**
 * 主世界地形填充器。逻辑全在 {@link AbstractTerrainFiller}：fill 从 level 现取该维度的
 * TerrainSystem 与 generatorSettings，因此本类只作为按维度区分的落点存在，将来按维度
 * 传构造参数时改这里。
 */
public final class OverworldTerrainFiller extends AbstractTerrainFiller {

    public OverworldTerrainFiller() {
    }
}
