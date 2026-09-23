package com.inf.farlands.terrain.system.biome;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.system.biome.misc.Void.VoidBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Oct.OctBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Vanilla.VanillaBiomeSystem;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 群系系统工厂：按维度在 ServerLevel 实例化时建一份，形状对齐 TerrainSystemFactory。
 * 实现无状态，实例随 level 走。
 */
public final class BiomeSystemFactory {

    private BiomeSystemFactory() {
    }

    public static BiomeSystem create(ResourceKey<Level> dimension) {
        return switch (typeFor(dimension)) {
            case VOID -> new VoidBiomeSystem();
            case VANILLA_OVERWORLD -> new VanillaBiomeSystem();
            case OCT -> new OctBiomeSystem(0L, 16.0, 16.0, 16.0);
        };
    }

    private static BiomeSystemType typeFor(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return FarlandsConfig.netherBiomeSystem;
        }
        if (dimension == Level.END) {
            return FarlandsConfig.endBiomeSystem;
        }
        return FarlandsConfig.overworldBiomeSystem;
    }
}
