package com.inf.farlands.mixin.fix.xyz;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityFluidInteraction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 流体交互盒的循环上界在极端 Y 环绕。
 *
 * <p>
 * update 用 {@code Mth.ceil(box.maxY) - 1} 求三重循环的上界。实体掉到 int 下界之下后，Mth.ceil 把
 * double 窄化成 int 时饱和成 Integer.MIN_VALUE，紧随的那次减一环绕成 Integer.MAX_VALUE，于是这条
 * 递增循环从 MIN_VALUE 数到 MAX_VALUE、到顶再回绕，永不结束；同一组界还喂给 hasFluidAndLoaded 的
 * section 循环，量级同理。客户端卡在自己的 tick 里，连服务端的纠正都收不到。
 *
 * <p>
 * 处理体把饱和值抬一格，让那次减一落在 MIN_VALUE 上：循环上界变成可表示的最底一格，越界侧少算一格，
 * 而那一格在可表示范围之外。update 里三处 Mth.ceil 对应 x/y/z 三个轴，各一条重定向，共用同一个算法。
 */
@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 0))
    private static int farlands$ceilNoWrapX(double value) {
        return farlands$ceilNoWrap(value);
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 1))
    private static int farlands$ceilNoWrapY(double value) {
        return farlands$ceilNoWrap(value);
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 2))
    private static int farlands$ceilNoWrapZ(double value) {
        return farlands$ceilNoWrap(value);
    }

    /** 饱和值抬一格，使调用点的那次减一不环绕。 */
    @Unique
    private static int farlands$ceilNoWrap(double value) {
        int ceiled = Mth.ceil(value);
        return ceiled == Integer.MIN_VALUE ? Integer.MIN_VALUE + 1 : ceiled;
    }
}
