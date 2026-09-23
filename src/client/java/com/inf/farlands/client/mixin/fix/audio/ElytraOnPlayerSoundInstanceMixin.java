package com.inf.farlands.client.mixin.fix.audio;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.ElytraOnPlayerSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鞘翅飞行音的位置改用玩家的 double 坐标。
 *
 * <p>
 * vanilla 在 {@code tick} 里写 {@code this.x = (float)player.getX()}，字节码是
 * {@code getX()D} 紧跟 {@code d2f} 再 {@code f2d} 最后 {@code putfield x:D}：字段本身是
 * double，中间多了一次 float 往返。float 的精度随绝对值下降，2^24 即约 1677 万处 ulp 已是
 * 1 格，2^31 处是 128 格；实体与相机同处那个量级时两者被舍入到同一格点，相减为 0。
 */
@Mixin(ElytraOnPlayerSoundInstance.class)
public abstract class ElytraOnPlayerSoundInstanceMixin extends AbstractTickableSoundInstance {

    @Shadow
    @Final
    private LocalPlayer player;

    /**
     * 只为让本类通过编译：目标类的父类只有带参构造器。本实例永不被构造，参数全部为占位。
     */
    @SuppressWarnings("DataFlowIssue")
    protected ElytraOnPlayerSoundInstanceMixin() {
        super((SoundEvent) null, (SoundSource) null, SoundInstance.createUnseededRandom());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void farlands$preciseCoordsOnTick(CallbackInfo ci) {
        this.x = this.player.getX();
        this.y = this.player.getY();
        this.z = this.player.getZ();
    }
}
