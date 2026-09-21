package com.inf.farlands.client.mixin.fix.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体绑定音的位置改用实体的 double 坐标。
 *
 * <p>
 * vanilla 在构造器与 {@code tick} 里写的是 {@code this.x = (float)entity.getX()}，
 * 字节码是 {@code d2f} 紧跟 {@code f2d}：经一次 float 往返。float 的精度随绝对值下降，
 * 2^24 即约 1677 万处 ulp 已是 1 格，2^31 处 ulp 是 128 格；实体与相机都在那个量级时
 * 两者被舍入到同一个格点，相减为 0，声音失去方位与距离。本 mod 的可玩范围是 ±2^31。
 *
 * <p>
 * 去掉这次 float 往返即可：{@code AbstractSoundInstance.x/y/z} 本就是 double，实体坐标
 * 也是 double，中间不需要 float。
 *
 * <p>
 * 听觉上位置最终仍会在 OpenAL 边界被压成 float，那一步由
 * {@code client.mixin.fix.audio.SoundEngineMixin} 以「减去听者位置」处理。两处修的是不同
 * 的截断点，缺任一处都会在极端坐标下失去方位。
 *
 * <p>
 * 本类只存在于 clientonly jar，因此整个 Mixin 放在 client 源集。
 */
@Mixin(EntityBoundSoundInstance.class)
public abstract class EntityBoundSoundInstanceMixin extends AbstractTickableSoundInstance {

    @Shadow
    @Final
    private Entity entity;

    /**
     * 只为让本 Mixin 类通过编译：它的父类只有带参构造器。本实例永不被构造，参数全部为
     * 占位，不参与注入，也不影响目标类自身的构造器。
     */
    @SuppressWarnings("DataFlowIssue")
    protected EntityBoundSoundInstanceMixin() {
        super((SoundEvent) null, (SoundSource) null, RandomSource.create());
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void farlands$entityCoords(SoundEvent event, SoundSource source, float volume, float pitch,
            Entity entity, long seed, CallbackInfo ci) {
        this.x = this.entity.getX();
        this.y = this.entity.getY();
        this.z = this.entity.getZ();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void farlands$entityCoordsTick(CallbackInfo ci) {
        Entity bound = this.entity;
        if (bound != null && !bound.isRemoved()) {
            this.x = bound.getX();
            this.y = bound.getY();
            this.z = bound.getZ();
        }
    }
}
