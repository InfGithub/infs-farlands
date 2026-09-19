package com.inf.farlands.terrain.system;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.system.misc.surfaceVoid.VoidSurfaceSystem;
import com.inf.farlands.terrain.system.overworld.surfaceVanilla.VanillaSurfaceSystem;

/**
 * 地表系统注册表，按维度懒建单例，形状对齐 NoiseSystemRegistry。
 * 当前 create 的 switch 有 VOID 与 VANILLA_OVERWORLD。实现无状态，单例共享安全。
 */
public final class SurfaceSystemRegistry {

    private static volatile SurfaceSystem overworld;
    private static volatile SurfaceSystem nether;
    private static volatile SurfaceSystem end;

    private SurfaceSystemRegistry() {
    }

    public static SurfaceSystem getOverworld() {
        SurfaceSystem s = overworld;
        if (s == null) {
            synchronized (SurfaceSystemRegistry.class) {
                s = overworld;
                if (s == null) {
                    s = create(FarlandsConfig.overworldSurfaceSystem);
                    overworld = s;
                }
            }
        }
        return s;
    }

    public static SurfaceSystem getTheNether() {
        SurfaceSystem s = nether;
        if (s == null) {
            synchronized (SurfaceSystemRegistry.class) {
                s = nether;
                if (s == null) {
                    s = create(FarlandsConfig.netherSurfaceSystem);
                    nether = s;
                }
            }
        }
        return s;
    }

    public static SurfaceSystem getTheEnd() {
        SurfaceSystem s = end;
        if (s == null) {
            synchronized (SurfaceSystemRegistry.class) {
                s = end;
                if (s == null) {
                    s = create(FarlandsConfig.endSurfaceSystem);
                    end = s;
                }
            }
        }
        return s;
    }

    private static SurfaceSystem create(SurfaceSystemType type) {
        return switch (type) {
            case VOID -> new VoidSurfaceSystem();
            case VANILLA_OVERWORLD -> new VanillaSurfaceSystem();
        };
    }
}
