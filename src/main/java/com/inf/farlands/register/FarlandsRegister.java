package com.inf.farlands.register;

import com.inf.farlands.command.FarlandsCommandRegistry;
import com.inf.farlands.register.command.FarLandsCommands;
import com.inf.farlands.register.packet.*;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.system.biome.misc.Void.VoidBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Oct.OctBiomeSystem;
import com.inf.farlands.terrain.system.biome.overworld.Vanilla.VanillaBiomeSystem;
import com.inf.farlands.terrain.system.carver.misc.Void.VoidCarverSystem;
import com.inf.farlands.terrain.system.carver.overworld.Vanilla.VanillaCarverSystem;
import com.inf.farlands.terrain.system.surface.misc.Void.VoidSurfaceSystem;
import com.inf.farlands.terrain.system.surface.overworld.Vanilla.VanillaSurfaceSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Hex.HexNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Void.VoidNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.misc.Weierstrass.WeierstrassNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Oct.OctNoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;

public class FarlandsRegister {
    public static void registerStatic() {
        ChunkDataPacketRegister.registerType();
        ClampStatePacketRegister.registerType();
        ClampTogglePacketRegister.registerType();
        LightUpdatePacketRegister.registerType();
        SectionBlocksUpdatePacketRegister.registerType();
        SystemsPacketRegister.registerType();
        registerSystems();
    }

    /** 12 个内置系统按四族登记，id 与实现类的对应关系集中在此。 */
    private static void registerSystems() {
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_VOID_NOISE_SYSTEM, VoidNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_VANILLA_NOISE_SYSTEM,
                VanillaNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_HEX_NOISE_SYSTEM, HexNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_WEIERSTRASS_NOISE_SYSTEM,
                WeierstrassNoiseSystem.class);
        SystemRegistries.registerTerrain(SystemRegistries.TERRAIN_OCT_NOISE_SYSTEM, OctNoiseSystem.class);

        SystemRegistries.registerBiome(SystemRegistries.BIOME_VOID_BIOME_SYSTEM, VoidBiomeSystem.class);
        SystemRegistries.registerBiome(SystemRegistries.BIOME_VANILLA_BIOME_SYSTEM,
                VanillaBiomeSystem.class);
        SystemRegistries.registerBiome(SystemRegistries.BIOME_OCT_BIOME_SYSTEM, OctBiomeSystem.class);

        SystemRegistries.registerSurface(SystemRegistries.SURFACE_VOID_SURFACE_SYSTEM,
                VoidSurfaceSystem.class);
        SystemRegistries.registerSurface(SystemRegistries.SURFACE_VANILLA_SURFACE_SYSTEM,
                VanillaSurfaceSystem.class);

        SystemRegistries.registerCarver(SystemRegistries.CARVER_VOID_CARVER_SYSTEM,
                VoidCarverSystem.class);
        SystemRegistries.registerCarver(SystemRegistries.CARVER_VANILLA_CARVER_SYSTEM,
                VanillaCarverSystem.class);
    }

    public static void register() {
        ClampTogglePacketRegister.registerHandler();
        // 命令监听器只在此登记一次；Commands 每次重建（含数据包 reload）时由 CommandsMixin 统一 fire。
        FarlandsCommandRegistry.registerServer(FarLandsCommands::register);
    }
}
