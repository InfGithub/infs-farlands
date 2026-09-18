package com.inf.farlands.mixin.light;

import com.inf.farlands.light.IColumnMasks;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.minecraft.world.level.chunk.PalettedContainer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LevelChunkSection 列级非空掩码，用于 fillFrom 加速，免逐格 palette 查找。
 *
 * 掩码维护：
 * - setBlockState，useLocks 版，无锁版委托它：@Inject RETURN 按写入 state 更新
 * 对应列行位，isEmpty = 非空方块
 * - read，fsa/网络读回：@Inject RETURN 置掩码 invalid，下次 ensure 重建扫 4096 格
 * - 懒创建：ensure 前 setBlockState 不维护，掩码为 null；ensure 时重建扫当前
 * states，含此前写入，正确
 *
 * 并发：fill 在 gen 线程 -> fillFrom 同 chunk 串行；掩码 per-section 无跨 section
 * 共享；setBlockState 与 ensure 同 chunk 串行，无竞争。
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements IColumnMasks {

    @Unique
    private short[] farlands$colMasks;

    @Unique
    private boolean farlands$colMasksValid;

    @Shadow
    @Final
    private PalettedContainer<BlockState> states;

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void onSetBlockState(int x, int y, int z, BlockState state, boolean useLocks,
            CallbackInfoReturnable<BlockState> cir) {
        short[] m = this.farlands$colMasks;
        if (m == null) {
            // 首次写入初始化掩码，即增量维护起点：fill 路径逐格写，初始化后逐格更新，
            // fill 完成掩码 = 实际状态，fillFrom ensure 不 rebuild（rebuild 扫 4096 格会抵消省）。
            m = new short[256];
            this.farlands$colMasks = m;
            this.farlands$colMasksValid = true;
        }
        int idx = x + z * 16;
        if (state.isAir()) {
            m[idx] &= (short) ~(1 << y);
        } else {
            m[idx] |= (short) (1 << y);
        }
    }

    @Inject(method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void onRead(FriendlyByteBuf buffer, CallbackInfo ci) {
        this.farlands$colMasks = null; // 内容已变，下次 ensure 重建
        this.farlands$colMasksValid = false;
    }

    @Override
    @Unique
    public short[] farlands$ensureColumnMasks() {
        if (this.farlands$colMasks == null || !this.farlands$colMasksValid) {
            rebuildColumnMasks();
        }
        return this.farlands$colMasks;
    }

    @Override
    @Unique
    public short[] farlands$columnMasksIfReady() {
        return this.farlands$colMasksValid ? this.farlands$colMasks : null;
    }

    @Unique
    private void rebuildColumnMasks() {
        short[] m = new short[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int idx = x + z * 16;
                short mask = 0;
                for (int y = 0; y < 16; y++) {
                    if (!this.states.get(x, y, z).isAir()) {
                        mask |= (short) (1 << y);
                    }
                }
                m[idx] = mask;
            }
        }
        this.farlands$colMasks = m;
        this.farlands$colMasksValid = true;
    }
}
