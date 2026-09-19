package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.world.level.levelgen.NoiseRouterData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NoiseRouterData.bootstrap 里 DimensionType.MIN_Y/MAX_Y 的读取点饱和。
 *
 * 三处读取都是 *2 之前的那次 getstatic：
 * belowBottom = MIN_Y * 2、aboveTop = MAX_Y * 2
 * veinMinY / veinMaxY 的兜底值 -MIN_Y * 2
 *
 * DimensionType 的竖直度量取满量程后这三个乘积都会回绕。把读取换成饱和值，随后那句 imul 2
 * 就落在 int 内。
 */
@Mixin(NoiseRouterData.class)
public class NoiseRouterDataMixin {

    /** ±MAX_BLOCK / 2：乘 2 后仍落在 int 内的最大对称边界。 */
    @Unique
    private static final int FARLANDS_SATURATED = FarlandsConstant.MAX_BLOCK / 2;

    @Redirect(method = "bootstrap", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/dimension/DimensionType;MIN_Y:I"))
    private static int farlands$minY() {
        return -FARLANDS_SATURATED;
    }

    @Redirect(method = "bootstrap", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/dimension/DimensionType;MAX_Y:I"))
    private static int farlands$maxY() {
        return FARLANDS_SATURATED;
    }
}
