package com.inf.farlands.terrain.system;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.system.misc.carverVoid.VoidCarverSystem;
import com.inf.farlands.terrain.system.overworld.carverVanilla.VanillaCarverSystem;

/**
 * 雕刻系统注册表，按维度懒建单例，形状对齐 NoiseSystemRegistry。
 * 当前 create 的 switch 有 VOID 与 VANILLA_OVERWORLD。实现无状态，单例共享安全。
 */
public final class CarverSystemRegistry {

    private static volatile CarverSystem overworld;
    private static volatile CarverSystem nether;
    private static volatile CarverSystem end;

    private CarverSystemRegistry() {
    }

    public static CarverSystem getOverworld() {
        CarverSystem s = overworld;
        if (s == null) {
            synchronized (CarverSystemRegistry.class) {
                s = overworld;
                if (s == null) {
                    s = create(FarlandsConfig.overworldCarverSystem);
                    overworld = s;
                }
            }
        }
        return s;
    }

    public static CarverSystem getTheNether() {
        CarverSystem s = nether;
        if (s == null) {
            synchronized (CarverSystemRegistry.class) {
                s = nether;
                if (s == null) {
                    s = create(FarlandsConfig.netherCarverSystem);
                    nether = s;
                }
            }
        }
        return s;
    }

    public static CarverSystem getTheEnd() {
        CarverSystem s = end;
        if (s == null) {
            synchronized (CarverSystemRegistry.class) {
                s = end;
                if (s == null) {
                    s = create(FarlandsConfig.endCarverSystem);
                    end = s;
                }
            }
        }
        return s;
    }

    private static CarverSystem create(CarverSystemType type) {
        return switch (type) {
            case VOID -> new VoidCarverSystem();
            case VANILLA_OVERWORLD -> new VanillaCarverSystem();
        };
    }
}
