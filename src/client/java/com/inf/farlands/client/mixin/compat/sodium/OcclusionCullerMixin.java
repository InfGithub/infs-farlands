package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.util.window.WindowedChunk;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * init 用 Level 的 section 范围判断视口原点是否在世界高度内。Level 基准是 -4 与 19，而本模组允许玩家
 * 在维度范围外，于是相机 section 一旦越出这两端就走 outOfWorld 分支，只在边界那一层铺菱形，玩家所在
 * section 的 RenderSection 永不入图。触发范围是方块 Y 不小于 320 或不大于 -65，正是窗口生成覆盖的两个
 * 区间。
 *
 * <p>
 * 判定基准换成玩家所在 chunk 的窗口。相机与玩家分离时窗口仍跟着相机滑动，两者的偏差在窗口半径
 * 之内，可接受。
 */
@Pseudo
@Mixin(OcclusionCuller.class)
public abstract class OcclusionCullerMixin {

    @WrapOperation(method = "init(Lnet/caffeinemc/mods/sodium/client/util/collections/WriteQueue;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSectionY()I"))
    private int farlands$windowMin(Level instance, Operation<Integer> original) {
        ChunkAccess chunk = farlands$playerChunk(instance);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMinY();
    }

    @WrapOperation(method = "init(Lnet/caffeinemc/mods/sodium/client/util/collections/WriteQueue;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxSectionY()I"))
    private int farlands$windowMax(Level instance, Operation<Integer> original) {
        ChunkAccess chunk = farlands$playerChunk(instance);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMaxY();
    }

    @Unique
    private static ChunkAccess farlands$playerChunk(Level level) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        return level.getChunk(
                SectionPos.blockToSectionCoord(player.getBlockX()),
                SectionPos.blockToSectionCoord(player.getBlockZ()));
    }
}
