package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.FarlandsConstant;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.caffeinemc.mods.sodium.client.model.light.data.ArrayLightDataCache;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * reset 把 origin 的最小方块端点减 2 当整块光照数组的偏移：xOffset = minBlockX() - 2，三轴各一。
 * index 里用方块坐标减这个偏移取整块下标，所以偏移一旦回绕，下标就是巨大的负数，this.light[l] 越界。
 *
 * <p>
 * 只有减、没有加，所以正侧（端点接近 int 上界）不回绕，需要夹的只有三个最小端点。
 */
@Pseudo
@Mixin(ArrayLightDataCache.class)
public abstract class ArrayLightDataCacheMixin {

    /** 邻域算式允许的最小端点，代回原地那次 -2 后仍停在 int 内。 */
    @Unique
    private static final int farlands$minEndpoint = ~(FarlandsConstant.MAX_BLOCK - 2);

    @WrapOperation(method = "reset", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockX()I"))
    private int farlands$satMinBlockX(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint + 2 ? farlands$minEndpoint + 2 : value;
    }

    @WrapOperation(method = "reset", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockY()I"))
    private int farlands$satMinBlockY(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint + 2 ? farlands$minEndpoint + 2 : value;
    }

    @WrapOperation(method = "reset", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockZ()I"))
    private int farlands$satMinBlockZ(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint + 2 ? farlands$minEndpoint + 2 : value;
    }
}
