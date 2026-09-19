package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NoiseBasedChunkGenerator.createFluidPicker 里 emptyStatus 液面的 MIN_Y * 2 饱和。
 *
 * DimensionType 的竖直度量取满量程后 MIN_Y * 2 会回绕成一个大正数，空气液面就跑到世界顶上去了。
 * 换成饱和值后 imul 2 落在 int 内，仍在世界之下。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorOverflowMixin {

    /** ±MAX_BLOCK / 2：乘 2 后仍落在 int 内的最大对称边界。 */
    @Unique
    private static final int FARLANDS_SATURATED = FarlandsConstant.MAX_BLOCK / 2;

    @Redirect(method = "createFluidPicker", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/dimension/DimensionType;MIN_Y:I"))
    private static int farlands$minY() {
        return -FARLANDS_SATURATED;
    }
}
