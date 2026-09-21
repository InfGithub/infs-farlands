package com.inf.farlands.terrain.terrainFiller;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 维度地形填充器工厂：按维度在 ServerLevel 实例化时建一份。
 *
 * <p>三个 filler 只要空参构造，它们与维度系统的绑定改由 level 侧给出（LevelSystems.terrainFiller），
 * 所以这里只负责按维度选实现类，不传任何参数。
 */
public final class TerrainFillerFactory {

    private TerrainFillerFactory() {
    }

    public static TerrainFiller create(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            return new TheNetherTerrainFiller();
        }
        if (level.dimension() == Level.END) {
            return new TheEndTerrainFiller();
        }
        return new OverworldTerrainFiller();
    }
}
