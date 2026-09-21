package com.inf.farlands.terrain.system.terrain;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Void.VoidNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 地形系统工厂：按维度在 ServerLevel 实例化时建一份。
 *
 * <p>当前 create 的 switch 有 VOID 与 VANILLA_OVERWORLD，加回系统时只需在枚举与 switch 各加一项。
 * 系统实例由调用方持有，本类不再缓存任何东西——实例随 level 走，进程级单例已取消。
 */
public final class TerrainSystemFactory {

    private TerrainSystemFactory() {
    }

    public static TerrainSystem create(ResourceKey<Level> dimension) {
        return switch (typeFor(dimension)) {
            case VOID -> new VoidNoiseSystem();
            case VANILLA_OVERWORLD -> new VanillaNoiseSystem();
        };
    }

    private static TerrainSystemType typeFor(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return FarlandsConfig.netherTerrainSystem;
        }
        if (dimension == Level.END) {
            return FarlandsConfig.endTerrainSystem;
        }
        return FarlandsConfig.overworldTerrainSystem;
    }
}
