package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConstant;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DensityFunctions$YClampedGradient 的 codec 边界饱和。
 *
 * YClampedGradient 是私有嵌套 record，只能 targets 字符串引用。实现在 group lambda lambda$static$0 里：
 * Codec.intRange(DimensionType.MIN_Y * 2, DimensionType.MAX_Y * 2)，from_y 与 to_y 各一次。
 * DimensionType 的竖直度量取满量程后那两个 *2 会越界回绕，把边界翻成相反数。
 *
 * 把读取换成饱和值，随后那句 imul 2 就落在 int 内。饱和值取 MAX_BLOCK / 2，这正是"再乘 2
 * 仍不溢出 int"的最大对称边界。
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient")
public class YClampedGradientOverflowMixin {

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
