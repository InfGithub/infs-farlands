package com.inf.farlands.terrain.system.terrain;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Void.VoidNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 地形系统注册表，按维度懒建单例并转发生命周期回调。
 *
 * 三维度各自独立入口，各自读 Config 的对应项。当前 create 的 switch 有 VOID 与 VANILLA_OVERWORLD，
 * 加回系统时只需在枚举与 switch 各加一项。线程安全用 volatile 加双重检查锁，
 * 首次创建在 onLevelLoad 或首个 fill，provider 首次 get 时定型，运行中改配置不重建。
 */
public final class TerrainSystemRegistry {

    private static volatile TerrainSystem overworld;
    private static volatile TerrainSystem nether;
    private static volatile TerrainSystem end;

    private TerrainSystemRegistry() {
    }

    public static TerrainSystem getOverworld() {
        TerrainSystem s = overworld;
        if (s == null) {
            synchronized (TerrainSystemRegistry.class) {
                s = overworld;
                if (s == null) {
                    s = create(FarlandsConfig.overworldTerrainSystem);
                    overworld = s;
                }
            }
        }
        return s;
    }

    public static TerrainSystem getTheNether() {
        TerrainSystem s = nether;
        if (s == null) {
            synchronized (TerrainSystemRegistry.class) {
                s = nether;
                if (s == null) {
                    s = create(FarlandsConfig.netherTerrainSystem);
                    nether = s;
                }
            }
        }
        return s;
    }

    public static TerrainSystem getTheEnd() {
        TerrainSystem s = end;
        if (s == null) {
            synchronized (TerrainSystemRegistry.class) {
                s = end;
                if (s == null) {
                    s = create(FarlandsConfig.endTerrainSystem);
                    end = s;
                }
            }
        }
        return s;
    }

    private static TerrainSystem create(TerrainSystemType type) {
        return switch (type) {
            case VOID -> new VoidNoiseSystem();
            case VANILLA_OVERWORLD -> new VanillaNoiseSystem();
        };
    }

    public static void onLevelLoad(long seed) {
        getOverworld().onLevelLoad(seed);
    }

    public static void onChunkFillStart(ChunkAccess chunk) {
        getOverworld().onChunkFillStart(chunk);
    }
}
