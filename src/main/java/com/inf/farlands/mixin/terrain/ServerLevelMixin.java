package com.inf.farlands.mixin.terrain;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsHolder;
import com.inf.farlands.terrain.registry.SystemsIO;
import com.inf.farlands.terrain.terrainFiller.TerrainFiller;
import com.inf.farlands.terrain.terrainFiller.TerrainFillerFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ServerLevel 构造末尾按该世界的系统配置建该 level 的四族系统，并作为 LevelSystems 与
 * SystemsHolder 的取值入口。
 *
 * <p>配置是世界一份，只在本世界第一个构造的 level 上解析一次。MinecraftServer.overworld() 在主世界
 * 构造期间为 null，因为 levels 的写入在主世界构造之后，其余维度的构造晚于那次写入，于是同一个判据
 * 同时给出由谁解析与从谁取用。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements LevelSystems, SystemsHolder {

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
    @Unique
    private SystemsData farlandsSystems;

    /**
     * 构造末尾建系统。此处 getChunkSource() 已建，filler 取 generatorSettings 成立。
     *
     * <p>配置的解析与写回都在 {@link SystemsIO}，这里只决定由谁解析、从谁取用；解析失败直接抛出，
     * 构造随之中止，不会留下半个 level。
     */
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/world/level/storage/ServerLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/dimension/LevelStem;ZJLjava/util/List;Z)V", at = @At("RETURN"))
    private void farlands$createSystems(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server.overworld();
        SystemsData data;
        if (overworld == null) {
            data = SystemsIO.resolve(server);
            this.farlandsSystems = data;
        } else {
            data = ((SystemsHolder) overworld).systems();
        }
        SystemsData.LevelSelection selection = SystemsIO.selection(data,
                level.dimension().identifier());
        this.farlandsTerrainSystem = SystemsIO.terrain(selection);
        this.farlandsBiomeSystem = SystemsIO.biome(selection);
        this.farlandsSurfaceSystem = SystemsIO.surface(selection);
        this.farlandsCarverSystem = SystemsIO.carver(selection);
        this.farlandsTerrainFiller = TerrainFillerFactory.create(level);
    }

    @Override
    public SystemsData systems() {
        return this.farlandsSystems;
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
