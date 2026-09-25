package com.inf.farlands.client.mixin.outside;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 六面体边界：泛红距离三维化。
 *
 * <p>
 * vanilla 的 extractVignette 取 {@code getDistanceToBorder(entity)}，只有 XZ，贴着地板或天花板
 * 不泛红。这里换成 {@code min(XZ 距离, Y 距离)}，六个面统一。只改这一处调用点，距离方法本体不动。
 *
 * <p>
 * outside=true 时返回 {@code Double.MAX_VALUE}，即距离远到不触发泛红。
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Redirect(method = "extractVignette", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getDistanceToBorder(Lnet/minecraft/world/entity/Entity;)D"))
    private double farlands$borderDistanceIncludingY(WorldBorder worldBorder, Entity entity) {
        if (FarlandsConfig.outside) {
            return Double.MAX_VALUE;
        }
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        double dXZ = worldBorder.getDistanceToBorder(entity.getX(), entity.getZ());
        double y = entity.getY();
        double dY = Math.min(y + limit, limit - y);
        return Math.min(dXZ, dY);
    }
}
