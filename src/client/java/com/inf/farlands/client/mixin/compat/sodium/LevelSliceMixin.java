package com.inf.farlands.client.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
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
 * prepare 用 Level 基准的索引取 chunk 的窗口数组，与窗口索引差 windowMinY + 4。改成目标 chunk 的窗口
 * 索引。prepare 由编译任务调用，传进来的 SectionPos 已经落在窗口内，越界只可能来自窗口滑动与编译的
 * 竞态，夹回窗口内即可。
 *
 * <p>
 * prepare(Level, SectionPos, ClonedChunkSectionCache) 是静态方法，参数槽 level=0, pos=1, cache=2，
 * 处理体必须同为静态。
 *
 * <p>
 * 取 chunk 用两参 Level.getChunk，与 Sodium 的取法同源：客户端对未加载 chunk 的两参查询返回共享的空
 * chunk 而不是 null，四参非阻塞形式返回 null，回落到 Level 基准索引就会在空 chunk 那个长度 1 的窗口
 * 数组上越界。
 */
@Pseudo
@Mixin(LevelSlice.class)
public abstract class LevelSliceMixin {

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionIndexFromSectionY(I)I"))
    private static int farlands$windowIndex(Level instance, int y, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) SectionPos pos) {
        ChunkAccess chunk = farlands$chunkAt(instance, pos.getX(), pos.getZ());
        if (chunk == null) {
            return original.call(instance, y);
        }
        int index = chunk.getSectionIndexFromSectionY(y);
        int length = chunk.getSections().length;
        if (index < 0 || index >= length) {
            return Math.max(0, Math.min(index, length - 1));
        }
        return index;
    }

    @Unique
    private static ChunkAccess farlands$chunkAt(Level level, int x, int z) {
        return level.getChunk(x, z);
    }
}
