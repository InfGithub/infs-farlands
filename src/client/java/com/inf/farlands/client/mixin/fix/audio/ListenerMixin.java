package com.inf.farlands.client.mixin.fix.audio;

import com.mojang.blaze3d.audio.Listener;

import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 OpenAL 听者的位置固定在原点，朝向保留。
 *
 * <p>{@code Listener.setTransform} 会把位置经 {@code d2f} 交给 {@code alListener3f}。float
 * 的精度随绝对值下降：2^31 处 ulp 是 128 格，听者与音源各自被舍入到最近的 128 格倍数，
 * 于是 16 格广播半径内的源与听者落到同一个 float 上，OpenAL 算出的距离恒为 0，声音按
 * 「就在你身上」播放，失去方位与衰减。
 *
 * <p>锚在 {@code transform.position()} 而不是改 {@code setTransform} 的入参：后者只能靠
 * {@code @ModifyVariable} 加 HEAD 注入点，而 HEAD 处没有 xLOAD 指令可供局部变量定位，能否
 * 命中取决于目标方法。改 {@code position()} 的返回值同样只影响交给 OpenAL 的那三个 float，
 * 朝向的两个向量原样透传。
 *
 * <p>听者归零后，音源必须同时改成「相对听者」的坐标，见 {@link SoundEngineMixin}。两者缺一
 * 不可：只归零会让所有声音挤到原点，只相对化会让听者位置被减两次。
 */
@Mixin(Listener.class)
public abstract class ListenerMixin {

    @Redirect(method = "setTransform", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/ListenerTransform;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 farlands$zeroListenerPosition(
            com.mojang.blaze3d.audio.ListenerTransform transform) {
        return Vec3.ZERO;
    }
}
