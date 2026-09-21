package com.inf.farlands.terrain.system.carver;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.system.carver.misc.Void.VoidCarverSystem;
import com.inf.farlands.terrain.system.carver.overworld.Vanilla.VanillaCarverSystem;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 雕刻系统工厂：按维度在 ServerLevel 实例化时建一份，形状对齐 TerrainSystemFactory。
 * 实现无状态，实例随 level 走。
 */
public final class CarverSystemFactory {

    private CarverSystemFactory() {
    }

    public static CarverSystem create(ResourceKey<Level> dimension) {
        return switch (typeFor(dimension)) {
            case VOID -> new VoidCarverSystem();
            case VANILLA_OVERWORLD -> new VanillaCarverSystem();
        };
    }

    private static CarverSystemType typeFor(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return FarlandsConfig.netherCarverSystem;
        }
        if (dimension == Level.END) {
            return FarlandsConfig.endCarverSystem;
        }
        return FarlandsConfig.overworldCarverSystem;
    }
}
