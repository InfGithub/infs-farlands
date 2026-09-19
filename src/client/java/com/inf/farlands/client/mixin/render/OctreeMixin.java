package com.inf.farlands.client.mixin.render;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.client.renderer.Octree;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * octree 根盒的竖直范围改成以相机为中心。
 *
 * vanilla 在 XZ 上把盒中心放在相机，竖直却在 boundingBoxSizeInSections >= sectionsPerChunk 时锚到
 * level.getMinY()，那是维度下界 -64。渲染网格竖直环绕之后槽位会跑到 -64 以下，盒外的 section 会落进
 * 一个不包含它的叶子盒，visitNodes 的视锥裁剪按错盒判，出现随视角变化的误裁。
 *
 * 竖直跨度不能沿用 boundingBoxSizeInSections：它由水平视距推出，rd=8 时只有 512 格即相机上下 16 段，
 * 小于网格的 ±17。这里按网格段数向上取到 2 的幂，与水平视距解耦。
 */
@Mixin(Octree.class)
public class OctreeMixin {

    @Shadow
    private BlockPos cameraSectionCenter;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 1)
    private int farlands$cameraCenteredMinY(int minY) {
        return this.cameraSectionCenter.getY() - farlands$halfSpanBlocks();
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 4)
    private int farlands$cameraCenteredMaxY(int maxY) {
        return this.cameraSectionCenter.getY() + farlands$halfSpanBlocks() - 1;
    }

    /** 网格竖直段数向上取到 2 的幂再折半，单位方块。取整到 2 的幂保证盒边与段边界对齐。 */
    @Unique
    private static int farlands$halfSpanBlocks() {
        int gridSections = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        return Mth.smallestEncompassingPowerOfTwo(gridSections) * 16 / 2;
    }
}
