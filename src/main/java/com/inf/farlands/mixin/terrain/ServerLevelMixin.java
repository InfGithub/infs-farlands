package com.inf.farlands.mixin.terrain;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.terrainFiller.TerrainFiller;
import com.inf.farlands.terrain.terrainFiller.TerrainFillerFactory;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 ServerLevel 构造末尾按 id 建该 level 的四族系统，并作为 LevelSystems 的取值入口。 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements LevelSystems {

    // ---- 每 level 一套地形相关系统 ----

    @Unique
    private TerrainSystem farlandsTerrainSystem;
    @Unique
    private BiomeSystem farlandsBiomeSystem;
    @Unique
    private SurfaceSystem farlandsSurfaceSystem;
    @Unique
    private CarverSystem farlandsCarverSystem;
    @Unique
    private TerrainFiller farlandsTerrainFiller;

    /**
     * 构造末尾建系统。此处 getChunkSource() 已建，filler 取 generatorSettings 成立。
     *
     * <p>
     * 过渡期选择写死在注入体里：地形用 vanilla 噪声系统，seed 取 0，与旧工厂的无参构造一致；其余
     * 三族仍是各自的 VOID。维度到 id 的解析与参数供给都不经配置，等接回配置时再补。
     */
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/world/level/storage/ServerLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/dimension/LevelStem;ZJLjava/util/List;Z)V", at = @At("RETURN"))
    private void farlands$createSystems(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        SystemArgs terrainArgs = new SystemArgs();
        terrainArgs.setLong("seed", 0L);
        this.farlandsTerrainSystem = SystemRegistries.terrain(
                SystemRegistries.TERRAIN_VANILLA_NOISE_SYSTEM, terrainArgs);
        this.farlandsBiomeSystem = SystemRegistries.biome(
                SystemRegistries.BIOME_VOID_BIOME_SYSTEM, new SystemArgs());
        this.farlandsSurfaceSystem = SystemRegistries.surface(
                SystemRegistries.SURFACE_VOID_SURFACE_SYSTEM, new SystemArgs());
        this.farlandsCarverSystem = SystemRegistries.carver(
                SystemRegistries.CARVER_VOID_CARVER_SYSTEM, new SystemArgs());
        this.farlandsTerrainFiller = TerrainFillerFactory.create(level);
    }

    @Override
    public TerrainSystem terrainSystem() {
        return this.farlandsTerrainSystem;
    }

    @Override
    public BiomeSystem biomeSystem() {
        return this.farlandsBiomeSystem;
    }

    @Override
    public SurfaceSystem surfaceSystem() {
        return this.farlandsSurfaceSystem;
    }

    @Override
    public CarverSystem carverSystem() {
        return this.farlandsCarverSystem;
    }

    @Override
    public TerrainFiller terrainFiller() {
        return this.farlandsTerrainFiller;
    }
}
