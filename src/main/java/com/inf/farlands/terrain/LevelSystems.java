package com.inf.farlands.terrain;

import com.inf.farlands.terrain.terrainFiller.TerrainFiller;

/**
 * 一个 level 实例所持有的地形相关系统。
 *
 * <p>由 ServerLevel 实现，每个 ServerLevel 实例化时各建一套，因此系统与维度一一对应，
 * 不再有进程级的按维度单例。四个系统与三个维度的 TerrainFiller 都是无状态实现，
 * 实例随 level 走是为了让将来按 level 传入构造参数时有落点。
 *
 * <p>取法统一为 {@code ((LevelSystems) level).xxxSystem()}，与 WindowedChunk 等
 * 既有接口注入同形。
 */
public interface LevelSystems {

    /** 该维度的地形系统。 */
    TerrainSystem terrainSystem();

    /** 该维度的群系系统。 */
    BiomeSystem biomeSystem();

    /** 该维度的地表系统。 */
    SurfaceSystem surfaceSystem();

    /** 该维度的雕刻系统。 */
    CarverSystem carverSystem();

    /** 该维度的地形填充器。 */
    TerrainFiller terrainFiller();
}
