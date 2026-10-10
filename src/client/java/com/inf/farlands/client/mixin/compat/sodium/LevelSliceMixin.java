package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.FarlandsConstant;
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
 * 七条处理体因此全部必须是静态方法：Mixin 判的是目标方法的修饰符，与被包裹的那次调用是实例调用还是
 * 静态调用无关。静态处理体拿不到 this，接收者由形参传入。
 *
 * <p>
 * 取 chunk 用两参 Level.getChunk，与 Sodium 的取法同源：客户端对未加载 chunk 的两参查询返回共享的空
 * chunk 而不是 null，四参非阻塞形式返回 null，回落到 Level 基准索引就会在空 chunk 那个长度 1 的窗口
 * 数组上越界。
 *
 * <p>
 * 另一组处理体管的是同一个方法里邻域盒的端点。邻域盒取 SectionPos 的六个块坐标端点各加减 2：
 * maxBlockX + 2 在 maxBlockX 为 2147483647 时越过 int 上界回绕成负数，构造器随即判出反序盒，跨度算
 * 出来是负数，volume.isInside 恒假，该 chunk 的邻域方块与光照一律读成空气。夹在端点算出来之后、构造
 * 器之前：端点本身不动，只把回绕后的值换成使那次加减落在 int 内的值。段号是这一段渲染身份的一部分，
 * 夹段号会让几何挂到别的 y 上，所以夹的是端点而不是 pos。
 */
@Pseudo
@Mixin(LevelSlice.class)
public abstract class LevelSliceMixin {

    /** 邻域算式允许的最大端点，代回原地那次 +2 后仍停在 int 内。 */
    @Unique
    private static final int farlands$maxEndpoint = FarlandsConstant.MAX_BLOCK - 2;

    /** 邻域算式允许的最小端点，与上界对称。 */
    @Unique
    private static final int farlands$minEndpoint = ~farlands$maxEndpoint;

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

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockX()I"))
    private static int farlands$satMinBlockX(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockY()I"))
    private static int farlands$satMinBlockY(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockZ()I"))
    private static int farlands$satMinBlockZ(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockX()I"))
    private static int farlands$satMaxBlockX(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockY()I"))
    private static int farlands$satMaxBlockY(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockZ()I"))
    private static int farlands$satMaxBlockZ(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
    }

    @Unique
    private static ChunkAccess farlands$chunkAt(Level level, int x, int z) {
        return level.getChunk(x, z);
    }

    /** 原地那次 -2 会下溢时返回一个使结果停在 int 内的值，否则放行原值。 */
    @Unique
    private static int farlands$clampMinus2(int value) {
        return value < farlands$minEndpoint + 2 ? farlands$minEndpoint + 2 : value;
    }

    /** 原地那次 +2 会溢出时返回一个使结果停在 int 内的值，否则放行原值。 */
    @Unique
    private static int farlands$clampPlus2(int value) {
        return value > farlands$maxEndpoint - 2 ? farlands$maxEndpoint - 2 : value;
    }
}
