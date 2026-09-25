package com.inf.farlands.mixin.outside;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 六面体边界：baseTick 的越界掉血判定三维化。
 *
 * <p>
 * vanilla 的判据是 {@code isWithinBounds(boundingBox)}，只有 XZ，站在地板或天花板之外不掉血。
 * 这里在 XZ 判定的基础上补 Y：脚部不高于地板、头部不低于天花板。取脚与头而不是整个 AABB，是因为
 * 贴着边界面站立时 box 本身必然跨出边界，按 box 整体判会把合法站位判成越界。
 *
 * <p>
 * 注入点是该分支里唯一的 {@code isWithinBounds(AABB)} 调用，26.1.2 把它放在
 * {@code instanceof ServerLevel} 分支内，因此只有服务端会走到。outside=true 时返回 true，
 * 越界伤害整体关闭。
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Redirect(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;isWithinBounds(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean farlands$checkBoundsIncludingY(WorldBorder worldBorder, AABB box) {
        if (FarlandsConfig.outside) {
            return true;
        }
        double limit = FarlandsConfig.borderAbsoluteMax - 16.0;
        return worldBorder.isWithinBounds(box) && box.minY <= limit && box.maxY >= -limit;
    }
}
