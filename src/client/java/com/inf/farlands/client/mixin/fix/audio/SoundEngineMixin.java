package com.inf.farlands.client.mixin.fix.audio;

import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 交给 OpenAL 的音源位置改成相对听者的偏移。
 *
 * <p>{@code Channel.setSelfPosition} 把位置经 {@code d2f} 交给 {@code alSourcefv}，误差由
 * 该位置的绝对值决定而不是由距离决定：|coord| = 2^31 时 ulp 是 128 格，16 格广播半径内
 * 的音源与听者被压到同一个 float，OpenAL 算出的距离恒为 0，声音失去方位与衰减。在
 * double 上先减掉听者位置，再交给那条 {@code d2f}，偏移量恒在广播半径以内，float 绰绰有余。
 *
 * <p>与 {@link ListenerMixin} 成对：听者归零，音源相对化。缺一不可，只做归零会让所有声音
 * 挤到原点，只做相对化会让听者位置被减两次。
 *
 * <p>听者位置在 {@code updateSource} 处缓存。该方法由 {@code Minecraft.runTick} 在主线程
 * 每帧调用，本类的两处 {@code @ModifyVariable} 也在主线程，读写同线程；volatile 只是兜底。
 *
 * <p>两处注入都锚在位置 Vec3 的 STORE 之后。Mixin 的 {@code @At("STORE")} 对
 * {@code @ModifyVariable} 匹配 STORE 的下一条指令，并把 handler 的返回值写回同一局部变量
 * slot，因此随后读取该 slot 的 {@code invokedynamic} 捕获到的是改后的值。两个方法里类型为
 * {@code Vec3} 的局部变量各只有一个，故 {@code ordinal} 用默认值。
 *
 * <p>{@code relative} 为真的实例，例如音乐、UI、环境音，其坐标本就是听者空间的，不能再减。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /** 本帧的听者位置。updateSource 写，两处 @ModifyVariable 读。 */
    @Unique
    private static volatile Vec3 farlands$listenerPos;

    /** 当前正在 play 的实例，供同一次调用的 @ModifyVariable 判断 relative。 */
    @Unique
    private static final ThreadLocal<SoundInstance> farlands$playing = new ThreadLocal<>();

    @Inject(method = "updateSource", at = @At("HEAD"))
    private void farlands$cacheListenerPos(Camera camera, CallbackInfo ci) {
        farlands$listenerPos = camera.position();
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void farlands$markPlaying(SoundInstance instance,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        farlands$playing.set(instance);
    }

    @Inject(method = "play", at = @At("RETURN"))
    private void farlands$clearPlaying(SoundInstance instance,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        farlands$playing.remove();
    }

    /** play：位置 Vec3 的 STORE 之后，随后它被捕获进 Channel 的 lambda。 */
    @ModifyVariable(method = "play", at = @At("STORE"), ordinal = 0)
    private Vec3 farlands$relativeForPlay(Vec3 position) {
        SoundInstance instance = farlands$playing.get();
        if (instance != null && instance.isRelative()) {
            return position;
        }
        return farlands$toListenerRelative(position);
    }

    /** tickInGameSound：tick 路径的实例都不设 relative，无条件相对化。 */
    @ModifyVariable(method = "tickInGameSound", at = @At("STORE"), ordinal = 0)
    private Vec3 farlands$relativeForTick(Vec3 position) {
        return farlands$toListenerRelative(position);
    }

    @Unique
    private static Vec3 farlands$toListenerRelative(Vec3 position) {
        Vec3 listenerPos = farlands$listenerPos;
        if (listenerPos == null || listenerPos == Vec3.ZERO) {
            return position;
        }
        return position.subtract(listenerPos);
    }
}
