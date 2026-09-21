package com.inf.farlands.terrain.system.surface;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.system.surface.misc.Void.VoidSurfaceSystem;
import com.inf.farlands.terrain.system.surface.overworld.Vanilla.VanillaSurfaceSystem;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 地表系统工厂：按维度在 ServerLevel 实例化时建一份，形状对齐 TerrainSystemFactory。
 * 实现无状态，实例随 level 走。
 */
public final class SurfaceSystemFactory {

    private SurfaceSystemFactory() {
    }

    public static SurfaceSystem create(ResourceKey<Level> dimension) {
        return switch (typeFor(dimension)) {
            case VOID -> new VoidSurfaceSystem();
            case VANILLA_OVERWORLD -> new VanillaSurfaceSystem();
        };
    }

    private static SurfaceSystemType typeFor(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return FarlandsConfig.netherSurfaceSystem;
        }
        if (dimension == Level.END) {
            return FarlandsConfig.endSurfaceSystem;
        }
        return FarlandsConfig.overworldSurfaceSystem;
    }
}
