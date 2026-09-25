package com.inf.farlands.mixin.outside;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 六面体边界：StaticBorderExtent 碰撞 shape 的 Y 有限化。
 *
 * <p>
 * vanilla 的 updateBox 把 shape 建成 {@code Shapes.box(minX, -Infinity, minZ, maxX, +Infinity, maxZ)}，
 * Y 向无界，实体可以穿过地板与天花板。这里把 Y 端收到 ±(borderAbsoluteMax - 16)，与 XZ 墙面用的
 * 那个值对齐，六面体因此闭合，两个水平面有物理碰撞。
 *
 * <p>
 * 该 shape 的取用点是 {@code CollisionGetter.borderCollision} 与
 * {@code Entity.collectColliders}，两处都先问 {@code WorldBorder.isInsideCloseToBorder}。所以
 * outside=true 时碰撞整体关闭，shape 本身不必跟着改，也就不会有 ±Infinity 的 AABB 进入碰撞计算。
 */
@Mixin(targets = "net.minecraft.world.level.border.WorldBorder$StaticBorderExtent")
public abstract class WorldBorder$StaticBorderExtentMixin {

    @Redirect(method = "updateBox", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;box(DDDDDD)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private static VoxelShape farlands$boxWithYBounds(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ) {
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        return Shapes.box(minX, -limit, minZ, maxX, limit, maxZ);
    }
}
