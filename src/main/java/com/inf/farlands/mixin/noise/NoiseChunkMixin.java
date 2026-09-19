package com.inf.farlands.mixin.noise;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.noisefiller.NoiseFillerContext;
import com.inf.farlands.terrain.system.NoiseSystemRegistry;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 NoiseChunk 构造时的 finalDensity 与 aquifer 换成当前维度的地形系统提供。
 *
 * 维度由 NoiseFillerContext 侧信道给出，DensityFunction 构造没有 level 引用，
 * 由各 NoiseFiller.fill 入口 set，出口 clear。
 *
 * 两处调用点各只有 1 个，均用 javap 在运行时 jar 与编译期 jar 核实过。
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;finalDensity()Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction farlands$replaceFinalDensity(NoiseRouter router) {
        TerrainSystem sys = switch (NoiseFillerContext.get()) {
            case NETHER -> NoiseSystemRegistry.getTheNether();
            case END -> NoiseSystemRegistry.getTheEnd();
            case OVERWORLD -> NoiseSystemRegistry.getOverworld();
        };
        return sys instanceof NoiseSystem n ? n.createFinalDensity(router) : DensityFunctions.zero();
    }

    /**
     * aquifer 由当前噪声系统提供，null 表示用默认 NoiseBasedAquifer。
     * 全空气系统的实现返回恒 AIR 且非 null 的 aquifer，使 MaterialRuleList 短路。
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private Aquifer farlands$replaceAquifer(NoiseChunk chunk, ChunkPos pos, NoiseRouter router,
            PositionalRandomFactory random, int minY, int height, Aquifer.FluidPicker picker) {
        TerrainSystem sys = switch (NoiseFillerContext.get()) {
            case NETHER -> NoiseSystemRegistry.getTheNether();
            case END -> NoiseSystemRegistry.getTheEnd();
            case OVERWORLD -> NoiseSystemRegistry.getOverworld();
        };
        Aquifer a = sys.createAquifer(chunk, pos, router, random, minY, height, picker);
        return a != null ? a : Aquifer.create(chunk, pos, router, random, minY, height, picker);
    }
}
