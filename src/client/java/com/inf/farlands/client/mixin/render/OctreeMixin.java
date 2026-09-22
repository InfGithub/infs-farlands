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
 * octree 根盒的三轴范围都改成以相机 section 中心为中。
 *
 * vanilla 只在水平方向拿相机 section 原点当基准，竖直方向在 boundingBoxSizeInSections >=
 * sectionsPerChunk 时锚到 level.getMinY()，那是维度下界 -64。渲染网格竖直环绕之后槽位会跑到
 * -64 以下，盒外的 section 会落进一个不包含它的叶子盒，visitNodes 的视锥裁剪按错盒判，出现
 * 随视角变化的误裁。
 *
 * 竖直跨度不能沿用 boundingBoxSizeInSections：它由水平视距推出，rd=8 时只有 512 格即相机上下 16 段，
 * 小于网格的 ±17。这里按网格段数向上取到 2 的幂，与水平视距解耦。
 *
 * <p>X/Z 一并改成以相机 section 中心为中。vanilla 那两轴用 {@code origin - distance} 起算，相机只是
 * 恰好落在盒内，并没有被放在中心；而相机 section 中心可到 ±2147483640，加半个跨度就越过 int 边界，
 * 在 int 里相加会回绕成负值，盒从构造那一刻就是反序的。
 *
 * <p>六条实参全部走 {@link #farlands$clampToIntBounds}。三轴的相机 section 中心都可以贴到
 * ±2147483640，加半个跨度都会越过 int 边界，只是 Y 在维度高度 -64 至 320 内永远碰不到、X/Z 在
 * 本 mod 的坐标范围下才暴露出来。
 *
 * <p>夹紧在端点会让盒的跨度从 1024 缩到约 512 至 520。这不影响渲染正确性：八叉树只用盒做视锥
 * 包络与距离判定，遍历按树结构走，所以盒偏小只会让包络更紧，不会让某个 section 被误剪。
 */
@Mixin(Octree.class)
public class OctreeMixin {

    @Shadow
    private BlockPos cameraSectionCenter;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 1)
    private int farlands$cameraCenteredMinY(int minY) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getY() - farlands$halfSpanBlocks());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 4)
    private int farlands$cameraCenteredMaxY(int maxY) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getY() + farlands$halfSpanBlocks() - 1L);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 0)
    private int farlands$cameraCenteredMinX(int minX) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getX() - farlands$halfSpanBlocks());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 3)
    private int farlands$cameraCenteredMaxX(int maxX) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getX() + farlands$halfSpanBlocks() - 1L);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 2)
    private int farlands$cameraCenteredMinZ(int minZ) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getZ() - farlands$halfSpanBlocks());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 5)
    private int farlands$cameraCenteredMaxZ(int maxZ) {
        return farlands$clampToIntBounds((long) this.cameraSectionCenter.getZ() + farlands$halfSpanBlocks() - 1L);
    }

    /** 网格竖直段数向上取到 2 的幂再折半，单位方块。取整到 2 的幂保证盒边与段边界对齐。 */
    @Unique
    private static int farlands$halfSpanBlocks() {
        int gridSections = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        return Mth.smallestEncompassingPowerOfTwo(gridSections) * 16 / 2;
    }

    /**
     * long 相加之后夹回 int 值域。X/Z 的相机段中心可以到 ±2147483640，加半个跨度就越过 int 边界，
     * 在 int 里相加会回绕成负值，BoundingBox 会因此得到反序参数并打 "inverted bounds"。
     */
    @Unique
    private static int farlands$clampToIntBounds(long value) {
        return (int) Mth.clamp(value, (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE);
    }
}
