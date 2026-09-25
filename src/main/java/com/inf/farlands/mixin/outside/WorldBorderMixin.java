package com.inf.farlands.mixin.outside;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 六面体边界：isInsideCloseToBorder 三维化。
 *
 * <p>
 * vanilla 只比 XZ：到边界墙的 XZ 距离小于 {@code d0 * 2}，且
 * {@code isWithinBounds(x, z, d0)}。这里把距离换成 {@code min(XZ 距离, Y 距离)}，并把 Y 范围一并
 * 纳入判定，实体贴近地板或天花板时边界墙的碰撞才参与，Y 向因此有物理面。
 *
 * <p>
 * 该方法是边界墙碰撞的唯一入口，两个调用点是 {@code CollisionGetter.borderCollision} 与
 * {@code Entity.collectColliders}。outside=true 时整体返回 false，XZ 与 Y 两面墙的碰撞一起关闭，
 * 实体可以离开世界。
 */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {

    @Shadow
    public abstract double getDistanceToBorder(double x, double z);

    @Overwrite
    public boolean isInsideCloseToBorder(Entity entity, AABB bounds) {
        if (FarlandsConfig.outside) {
            return false;
        }
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        double d0 = Math.max(Mth.absMax(bounds.getXsize(), bounds.getZsize()), 1.0);
        double dXZ = this.getDistanceToBorder(entity.getX(), entity.getZ());
        double y = entity.getY();
        double dY = Math.min(y + limit, limit - y);
        return Math.min(dXZ, dY) < d0 * 2.0 && y > -limit - d0 && y < limit + d0;
    }
}
