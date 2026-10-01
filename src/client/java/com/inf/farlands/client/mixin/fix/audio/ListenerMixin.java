package com.inf.farlands.client.mixin.fix.audio;

import com.inf.farlands.client.audio.AudioOrigin;
import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;

import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 听者位置改成锚点相对量，朝向保留。
 *
 * <p>
 * {@code Listener.setTransform} 把位置经 {@code d2f} 交给 {@code alListener3f}。绝对值到 2^31 时
 * ulp 是 128 格，听者与音源各自被舍入到最近的 128 格倍数，16 格内的源与听者落到同一个 float 上，
 * OpenAL 算出的距离恒为 0，声音失去方位与衰减。减掉锚点后交给 OpenAL 的只剩格内偏移。
 *
 * <p>
 * 锚点只从位置里算，不读任何共享变量。音源那一侧用同一帧的相机位置算同一个函数，见
 * {@link SoundEngineMixin}。两者缺一不可：只改一侧会让等效偏移整体平移一个格边长。
 *
 * <p>
 * 这里只改交给 OpenAL 的那三个 float。字节码里 {@code Listener.setTransform} 先把 transform 存进
 * 字段再取位置，所以字段里仍是世界坐标，读它的字幕叠加层不受影响。
 */
@Mixin(Listener.class)
public abstract class ListenerMixin {

    @Redirect(method = "setTransform", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/ListenerTransform;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 farlands$anchoredListenerPosition(ListenerTransform transform) {
        return AudioOrigin.shift(transform.position());
    }
}
