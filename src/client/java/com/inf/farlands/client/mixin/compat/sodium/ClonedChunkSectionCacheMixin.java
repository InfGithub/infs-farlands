package com.inf.farlands.client.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
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
 * Sodium 在 clone 里用 Level 基准的索引去取 chunk 的窗口数组。本模组把 isOutsideBuildHeight 放宽到
 * 世界生成界之后，维度外的 section 不再被拦截；getSectionIndexFromSectionY 在 Level 上得 y + 4，与
 * 窗口索引差 windowMinY + 4，窗口外的 section 直接越界。
 *
 * <p>
 * 两处都改成按目标 chunk 的窗口判定与取索引。窗口外的 section 返回 true，让 Sodium 走 null section
 * 的 EMPTY 路径；取索引越界时夹到窗口内，因为那只可能来自窗口滑动与编译任务的竞态。
 *
 * <p>
 * 取 chunk 用两参 Level.getChunk，与 Sodium 的取法同源：客户端对未加载 chunk 的两参查询返回共享的空
 * chunk 而不是 null，四参非阻塞形式返回 null，回落到 Level 基准索引就会在空 chunk 那个长度 1 的窗口
 * 数组上越界。
 */
@Pseudo
@Mixin(ClonedChunkSectionCache.class)
public abstract class ClonedChunkSectionCacheMixin {

    /** clone(int x, int y, int z) 的参数槽：this=0, x=1, y=2, z=3。 */
    @WrapOperation(method = "clone", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(I)Z"))
    private boolean farlands$windowOutside(Level instance, int y, Operation<Boolean> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 3) int z) {
        // 调用点传的是 sectionToBlockCoord(y)，handler 收到的是方块 Y，先转回 section 号。
        int sectionY = y >> 4;
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        if (chunk == null) {
            return original.call(instance, y);
        }
        int index = chunk.getSectionIndexFromSectionY(sectionY);
        return index < 0 || index >= chunk.getSections().length;
    }

    @WrapOperation(method = "clone", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionIndexFromSectionY(I)I"))
    private int farlands$windowIndex(Level instance, int y, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 3) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
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
