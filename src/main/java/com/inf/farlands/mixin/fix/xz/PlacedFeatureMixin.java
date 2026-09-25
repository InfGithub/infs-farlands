package com.inf.farlands.mixin.fix.xz;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 地物放置的极端 XZ 界判。
 *
 * <p>
 * 放置链上游按 chunk 范围拒绝，但坐标一旦落到可玩方块范围之外，后续的位置修饰符与柱状扫描会在
 * 数十亿格上展开。这里在入口按 {@code WorldBounds.inBlockXZ} 判一次，越界直接返回未放置。
 */
@Mixin(PlacedFeature.class)
public class PlacedFeatureMixin {

    @Inject(method = "placeWithBiomeCheck", at = @At("HEAD"), cancellable = true)
    private void farlands$skipExtremeCoords(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
            BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldBounds.inBlockXZ(origin.getX(), origin.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
