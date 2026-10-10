package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.FarlandsConstant;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.caffeinemc.mods.sodium.client.world.biome.LevelColorCache;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * update 从 ChunkRenderContext 的 origin 重算一遍邻域端点，与 LevelSlice.prepare 里那个盒同源：同样
 * 各加减 2，同样在端点接近 int 两端时回绕。回绕之后 getColor 的 Mth.clamp 在 max 小于 min 时返回
 * max，三轴经 int 回绕后落进数组界内，于是取到错的生物群系颜色，不报错。
 *
 * <p>
 * 这里的六个端点与 prepare 那六个是两处独立的算式，prep 的夹取不为它们兜底，所以各写一份。夹的
 * 时机一样：端点算出来之后、被搬进字段之前。
 */
@Pseudo
@Mixin(LevelColorCache.class)
public abstract class LevelColorCacheMixin {

    /** 邻域算式允许的最大端点，代回原地那次 +2 后仍停在 int 内。 */
    @Unique
    private static final int farlands$maxEndpoint = FarlandsConstant.MAX_BLOCK - 2;

    /** 邻域算式允许的最小端点，与上界对称。 */
    @Unique
    private static final int farlands$minEndpoint = ~farlands$maxEndpoint;

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockX()I"))
    private int farlands$satMinBlockX(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockY()I"))
    private int farlands$satMinBlockY(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockZ()I"))
    private int farlands$satMinBlockZ(SectionPos self, Operation<Integer> original) {
        return farlands$clampMinus2(original.call(self));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockX()I"))
    private int farlands$satMaxBlockX(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockY()I"))
    private int farlands$satMaxBlockY(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;maxBlockZ()I"))
    private int farlands$satMaxBlockZ(SectionPos self, Operation<Integer> original) {
        return farlands$clampPlus2(original.call(self));
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
