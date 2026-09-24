package com.inf.farlands.terrain.registry;

import java.util.List;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;

import net.minecraft.resources.Identifier;

/**
 * 系统注册表的静态门面：四张类型化表、12 个内置 id 常量、四个登记入口、四个取用入口与元信息。
 *
 * <p>门面不读配置也不引 level：维度到 id 的选择由调用点给出，注册表层因此不依赖任何上层。
 *
 * <p>id 用 InfsFarlands.MOD_ID 拼，不调 InfsFarlands.id：前者是编译期常量会被 javac 内联，不触发
 * InfsFarlands 的类初始化；后者是方法调用会触发，而 FarlandsRegister.registerStatic 正从
 * InfsFarlands 的静态块里调到本类，触发就会形成重入初始化。
 */
public final class SystemRegistries {

    private static final SystemRegistry<TerrainSystem> TERRAIN = new SystemRegistry<>(TerrainSystem.class);
    private static final SystemRegistry<BiomeSystem> BIOME = new SystemRegistry<>(BiomeSystem.class);
    private static final SystemRegistry<SurfaceSystem> SURFACE = new SystemRegistry<>(SurfaceSystem.class);
    private static final SystemRegistry<CarverSystem> CARVER = new SystemRegistry<>(CarverSystem.class);

    public static final SystemId TERRAIN_VOID_NOISE_SYSTEM = id("void_noise_system");
    public static final SystemId TERRAIN_VANILLA_NOISE_SYSTEM = id("vanilla_noise_system");
    public static final SystemId TERRAIN_HEX_NOISE_SYSTEM = id("hex_noise_system");
    public static final SystemId TERRAIN_WEIERSTRASS_NOISE_SYSTEM = id("weierstrass_noise_system");
    public static final SystemId TERRAIN_OCT_NOISE_SYSTEM = id("oct_noise_system");

    public static final SystemId BIOME_VOID_BIOME_SYSTEM = id("void_biome_system");
    public static final SystemId BIOME_VANILLA_BIOME_SYSTEM = id("vanilla_biome_system");
    public static final SystemId BIOME_OCT_BIOME_SYSTEM = id("oct_biome_system");

    public static final SystemId SURFACE_VOID_SURFACE_SYSTEM = id("void_surface_system");
    public static final SystemId SURFACE_VANILLA_SURFACE_SYSTEM = id("vanilla_surface_system");

    public static final SystemId CARVER_VOID_CARVER_SYSTEM = id("void_carver_system");
    public static final SystemId CARVER_VANILLA_CARVER_SYSTEM = id("vanilla_carver_system");

    private SystemRegistries() {
    }

    private static SystemId id(String path) {
        return new SystemId(Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, path));
    }

    // ---- 登记 ----

    public static void registerTerrain(SystemId id, Class<? extends TerrainSystem> type) {
        TERRAIN.register(id, type);
    }

    public static void registerBiome(SystemId id, Class<? extends BiomeSystem> type) {
        BIOME.register(id, type);
    }

    public static void registerSurface(SystemId id, Class<? extends SurfaceSystem> type) {
        SURFACE.register(id, type);
    }

    public static void registerCarver(SystemId id, Class<? extends CarverSystem> type) {
        CARVER.register(id, type);
    }

    // ---- 取用 ----

    public static TerrainSystem terrain(SystemId id, SystemArgs args) {
        return TERRAIN.newInstance(id, args);
    }

    public static BiomeSystem biome(SystemId id, SystemArgs args) {
        return BIOME.newInstance(id, args);
    }

    public static SurfaceSystem surface(SystemId id, SystemArgs args) {
        return SURFACE.newInstance(id, args);
    }

    public static CarverSystem carver(SystemId id, SystemArgs args) {
        return CARVER.newInstance(id, args);
    }

    // ---- 元信息 ----

    static SystemRegistry<TerrainSystem> terrainTable() {
        return TERRAIN;
    }

    static SystemRegistry<BiomeSystem> biomeTable() {
        return BIOME;
    }

    static SystemRegistry<SurfaceSystem> surfaceTable() {
        return SURFACE;
    }

    static SystemRegistry<CarverSystem> carverTable() {
        return CARVER;
    }

    public static List<SystemId> terrainIds() {
        return TERRAIN.ids();
    }

    public static List<SystemId> biomeIds() {
        return BIOME.ids();
    }

    public static List<SystemId> surfaceIds() {
        return SURFACE.ids();
    }

    public static List<SystemId> carverIds() {
        return CARVER.ids();
    }

    public static Class<? extends TerrainSystem> terrainClassOf(SystemId id) {
        return TERRAIN.classOf(id);
    }

    public static Class<? extends BiomeSystem> biomeClassOf(SystemId id) {
        return BIOME.classOf(id);
    }

    public static Class<? extends SurfaceSystem> surfaceClassOf(SystemId id) {
        return SURFACE.classOf(id);
    }

    public static Class<? extends CarverSystem> carverClassOf(SystemId id) {
        return CARVER.classOf(id);
    }

    public static boolean terrainContains(SystemId id) {
        return TERRAIN.contains(id);
    }

    public static boolean biomeContains(SystemId id) {
        return BIOME.contains(id);
    }

    public static boolean surfaceContains(SystemId id) {
        return SURFACE.contains(id);
    }

    public static boolean carverContains(SystemId id) {
        return CARVER.contains(id);
    }
}
