package com.inf.farlands.terrain.terrainFiller;

/**
 * 地形填充器的唯一实现。逻辑全在 {@link AbstractTerrainFiller}：fill 从 level 现取该维度的
 * TerrainSystem 与 generatorSettings，所以维度不参与分派，任何维度都走这一份。
 *
 * <p>此前有按维度分出的三个子类，但三者实现体全空，维度不参与任何判定，那层区分是假象。
 * 真需要按维度换填充器时，再在 {@code LevelSystems.terrainFiller()} 的构造点按维度取。
 */
public final class StandardTerrainFiller extends AbstractTerrainFiller {

    public StandardTerrainFiller() {
    }
}
