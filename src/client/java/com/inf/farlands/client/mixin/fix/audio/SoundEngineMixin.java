package com.inf.farlands.client.mixin.fix.audio;

import java.util.Map;

import com.inf.farlands.client.audio.AudioOrigin;

import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 交给 OpenAL 的音源位置改成锚点相对量，并在换格时重写全部在播音源。
 *
 * <p>
 * {@code Channel.setSelfPosition} 把位置经 {@code d2f} 交给 {@code alSourcefv}，误差由该位置的绝对
 * 值决定而不是由距离决定：绝对值到 2^31 时 ulp 是 128 格，16 格广播半径内的音源与听者被压到同一个
 * float，OpenAL 算出的距离恒为 0，声音失去方位与衰减。锚点与量化规则见 {@link AudioOrigin}。
 *
 * <p>
 * 写音源位置在 vanilla 里只有两处：{@code play} 里一次，{@code tickInGameSound} 里每 tick 一次且只
 * 覆盖 tickable 实例。两处都改成减当前锚点。非 tickable 的实例，例如唱片机，位置一辈子只写那一次，
 * 所以换格时必须把它们一起重写，否则它们的等效偏移停在旧锚点上，表现为方位锁死。
 *
 * <p>
 * {@code relative} 的实例坐标本就是听者空间的 0，减锚点会把它推到几十万格以外，因此三条改写路径都
 * 要放行它们。播放路径与重写循环用 {@code isRelative} 判断；tick 路径拿不到实例，改在坐标读取点上
 * 重定向，由接收者拿到实例。
 *
 * <p>
 * 锚点只由 {@code updateSource} 写，三条改写路径都读它，四者都在客户端主线程；听者那一侧不读它，
 * 由 {@link ListenerMixin} 从自己的 transform 现算。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /** 当前锚点。updateSource 写，另两条改写路径读。 */
    @Unique
    private static volatile Vec3 farlands$anchor;

    /** 当前正在 play 的实例，供同一次调用的位置改写判断 relative。 */
    @Unique
    private static final ThreadLocal<SoundInstance> farlands$playing = new ThreadLocal<>();

    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    /**
     * 换格时记录新锚点并把全部在播音源重写到它。
     *
     * <p>
     * 守卫不能省。{@code updateSource} 在相机未初始化或引擎未装载时直接返回，此时 vanilla 那一侧不
     * 写听者；这里若照常换锚点并重写，听者与音源会停在两格上，直到相机初始化。
     */
    @Inject(method = "updateSource", at = @At("HEAD"))
    private void farlands$refreshAnchor(Camera camera, CallbackInfo ci) {
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 anchor = AudioOrigin.quantize(camera.position());
        Vec3 previous = farlands$anchor;
        if (previous != null && AudioOrigin.sameAnchor(previous, anchor)) {
            return;
        }
        farlands$anchor = anchor;
        farlands$rewriteSources(anchor);
    }

    /**
     * 把全部非 relative 的在播音源重写到新锚点。位置从实例的绝对坐标重算，不用旧值加减，避免误差随
     * 换格次数累积。
     */
    @Unique
    private void farlands$rewriteSources(Vec3 anchor) {
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : this.instanceToChannel.entrySet()) {
            SoundInstance instance = entry.getKey();
            if (instance.isRelative()) {
                continue;
            }
            Vec3 shifted = new Vec3(
                    instance.getX() - anchor.x,
                    instance.getY() - anchor.y,
                    instance.getZ() - anchor.z);
            entry.getValue().execute(channel -> channel.setSelfPosition(shifted));
        }
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
        return farlands$toAnchorRelative(position);
    }

    /** tickInGameSound：位置的 X 读取点。接收者是实例本身，relative 判断只在这里拿得到。 */
    @Redirect(method = "tickInGameSound", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/TickableSoundInstance;getX()D"))
    private double farlands$relativeTickX(TickableSoundInstance instance) {
        Vec3 anchor = farlands$anchor;
        return instance.isRelative() || anchor == null ? instance.getX() : instance.getX() - anchor.x;
    }

    /** tickInGameSound：位置的 Y 读取点，与 X 同规。 */
    @Redirect(method = "tickInGameSound", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/TickableSoundInstance;getY()D"))
    private double farlands$relativeTickY(TickableSoundInstance instance) {
        Vec3 anchor = farlands$anchor;
        return instance.isRelative() || anchor == null ? instance.getY() : instance.getY() - anchor.y;
    }

    /** tickInGameSound：位置的 Z 读取点，与 X 同规。 */
    @Redirect(method = "tickInGameSound", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/TickableSoundInstance;getZ()D"))
    private double farlands$relativeTickZ(TickableSoundInstance instance) {
        Vec3 anchor = farlands$anchor;
        return instance.isRelative() || anchor == null ? instance.getZ() : instance.getZ() - anchor.z;
    }

    /**
     * 锚点未建立时原样返回。那一刻只可能是首次 updateSource 之前，听者仍是 {@code Listener} 构造时
     * 的 INITIAL 原点，两边同为世界坐标。
     */
    @Unique
    private static Vec3 farlands$toAnchorRelative(Vec3 position) {
        Vec3 anchor = farlands$anchor;
        if (anchor == null) {
            return position;
        }
        return position.subtract(anchor);
    }
}
