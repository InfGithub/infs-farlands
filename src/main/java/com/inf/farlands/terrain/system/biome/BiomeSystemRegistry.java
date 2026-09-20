package com.inf.farlands.terrain.system.biome;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.system.biome.misc.Void.VoidBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Vanilla.VanillaBiomeSystem;

/**
 * 群系系统注册表，按维度懒建单例，形状对齐 TerrainSystemRegistry。
 * 当前 create 的 switch 有 VOID 与 VANILLA_OVERWORLD。实现无状态，单例共享安全。
 */
public final class BiomeSystemRegistry {

    private static volatile BiomeSystem overworld;
    private static volatile BiomeSystem nether;
    private static volatile BiomeSystem end;

    private BiomeSystemRegistry() {
    }

    public static BiomeSystem getOverworld() {
        BiomeSystem s = overworld;
        if (s == null) {
            synchronized (BiomeSystemRegistry.class) {
                s = overworld;
                if (s == null) {
                    s = create(FarlandsConfig.overworldBiomeSystem);
                    overworld = s;
                }
            }
        }
        return s;
    }

    public static BiomeSystem getTheNether() {
        BiomeSystem s = nether;
        if (s == null) {
            synchronized (BiomeSystemRegistry.class) {
                s = nether;
                if (s == null) {
                    s = create(FarlandsConfig.netherBiomeSystem);
                    nether = s;
                }
            }
        }
        return s;
    }

    public static BiomeSystem getTheEnd() {
        BiomeSystem s = end;
        if (s == null) {
            synchronized (BiomeSystemRegistry.class) {
                s = end;
                if (s == null) {
                    s = create(FarlandsConfig.endBiomeSystem);
                    end = s;
                }
            }
        }
        return s;
    }

    private static BiomeSystem create(BiomeSystemType type) {
        return switch (type) {
            case VOID -> new VoidBiomeSystem();
            case VANILLA_OVERWORLD -> new VanillaBiomeSystem();
        };
    }
}
