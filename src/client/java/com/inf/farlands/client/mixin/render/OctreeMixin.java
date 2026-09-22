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
 * <p><b>盒宽必须恒等于 {@code 2 * halfSpanBlocks}，不能在端点处被压窄。</b>
 * {@code Octree$Branch.areChildrenLeaves} 的判据是 {@code getXSpan() == 32}，而下降每层只留一半，
 * 宽度按 {@code 1024, 512, 256, 128, 64, 32} 走，恰好踩到 32 才有出口。把越界那一端单独
 * 夹到 int 边界会把宽度改成 536、552、520、504 这类值，序列变成
 * {@code 536, 268, 134, 67, 33, 16}，<b>在 32 旁边跳过</b>，递归永不终止，
 * 异步全量重建抛 {@code StackOverflowError}，遮挡图建不出来，可见集为空，而编译只遍历可见集，
 * 于是所有 section 都停在 dirty 与 UNCOMPILED。
 *
 * <p>因此这里做的是整盒平移：先把相机中心夹进"整盒仍落在 int 内"的可行窗口，再按夹后的中心取
 * 两端。两端都由同一个中心决定，六个 {@code @ModifyArg} 各自重算同一个盒，互不依赖，无状态。
 * 正常坐标下窗口不生效，与平移前逐位相同。
 */
@Mixin(Octree.class)
public class OctreeMixin {

    @Shadow
    private BlockPos cameraSectionCenter;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 1)
    private int farlands$cameraCenteredMinY(int minY) {
        return farlands$boxMin(this.cameraSectionCenter.getY());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 4)
    private int farlands$cameraCenteredMaxY(int maxY) {
        return farlands$boxMax(this.cameraSectionCenter.getY());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 0)
    private int farlands$cameraCenteredMinX(int minX) {
        return farlands$boxMin(this.cameraSectionCenter.getX());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 3)
    private int farlands$cameraCenteredMaxX(int maxX) {
        return farlands$boxMax(this.cameraSectionCenter.getX());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 2)
    private int farlands$cameraCenteredMinZ(int minZ) {
        return farlands$boxMin(this.cameraSectionCenter.getZ());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"), index = 5)
    private int farlands$cameraCenteredMaxZ(int maxZ) {
        return farlands$boxMax(this.cameraSectionCenter.getZ());
    }

    /** 网格竖直段数向上取到 2 的幂再折半，单位方块。取整到 2 的幂保证盒边与段边界对齐。 */
    @Unique
    private static int farlands$halfSpanBlocks() {
        int gridSections = FarlandsConfig.verticalSimulationDistance * 2 + 1;
        return Mth.smallestEncompassingPowerOfTwo(gridSections) * 16 / 2;
    }

    /**
     * 把相机中心夹进"整盒仍落在 int 内、且转成 AABB 不溢出"的可行窗口。窗口外的中心会让
     * {@code center ± half} 越过 int，此时整盒内移，宽度不变。
     *
     * <p>上界取 {@code INT_MAX - half} 而不是 {@code INT_MAX - (width - 1 - half)}：后者允许
     * {@code maxX} 落到 {@code INT_MAX}，而 {@code AABB.of} 在 int 里做 {@code maxX + 1}，
     * 会溢出成 {@code INT_MIN}，AABB 从构造那刻起就是 {@code min > max} 的反序盒。反序盒进
     * {@code Frustum.cubeInFrustum} 判不可见，{@code visitNodes} 于是一个 section 都收不进
     * 八叉树，可见集为空，编译入口只遍历可见集，最终该 section 不渲染。留一位之后
     * {@code maxX ≤ INT_MAX - 1}，加一后正好落在 int 内。
     */
    @Unique
    private static long farlands$shiftedCenter(long center) {
        long half = farlands$halfSpanBlocks();
        long lowest = Integer.MIN_VALUE + half;
        long highest = (long) Integer.MAX_VALUE - half;
        return Mth.clamp(center, lowest, highest);
    }

    /** 盒的下端。与 {@link #farlands$boxMax} 共用同一个夹后的中心，因此宽度恒为 2 * halfSpanBlocks()。 */
    @Unique
    private static int farlands$boxMin(int center) {
        return (int) (farlands$shiftedCenter(center) - farlands$halfSpanBlocks());
    }

    /** 盒的上端。 */
    @Unique
    private static int farlands$boxMax(int center) {
        return (int) (farlands$shiftedCenter(center) + farlands$halfSpanBlocks() - 1L);
    }
}
