package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConstant;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DensityFunctions$FindTopSurface 的 codec 边界饱和。与
 * {@code YClampedGradientOverflowMixin} 同一个病。FindTopSurface 也是私有嵌套 record：<clinit> 里
 * {@code Codec.intRange(MIN_Y * 2, MAX_Y * 2)} 那个 lower_bound 会越界回绕。读取在 lambda$static$0 里。
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$FindTopSurface")
public class FindTopSurfaceOverflowMixin {

    /** ±MAX_BLOCK / 2：乘 2 后仍落在 int 内的最大对称边界。 */
    @Unique
    private static final int FARLANDS_SATURATED = FarlandsConstant.MAX_BLOCK / 2;

    @Redirect(method = "lambda$static$0", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/dimension/DimensionType;MIN_Y:I"))
    private static int farlands$minY() {
        return -FARLANDS_SATURATED;
    }

    @Redirect(method = "lambda$static$0", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/dimension/DimensionType;MAX_Y:I"))
    private static int farlands$maxY() {
        return FARLANDS_SATURATED;
    }
}
