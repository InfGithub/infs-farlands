package com.inf.farlands.mixin.serialize;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * per-section 脏标记的标脏点，供 fsa 序列化引擎使用。
 *
 * 玩家的 setBlock 与破坏最终都走 LevelChunk.setBlockState，都在主线程。只在修改实际发生
 * 时标脏，判据是返回值非 null，vanilla 在 blockstate 等于 state 的分支返回 null 表示没变。
 * 双端共享类，客户端标脏无害，SectionLifecycle 是纯服务端，客户端从不查询。
 *
 * 26.1.2 的签名是 setBlockState(BlockPos, BlockState, int flags)，1.21.1 是
 * setBlockState(BlockPos, BlockState, boolean isMoving)，已 javap 核实。
 *
 * 另一处标脏点是 terrain 的 fill，GenTask 逐段 fill 与 surface、carvers 升段后标脏。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSetBlockStateMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void farlands$markSectionDirty(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            ((WindowedChunk) this).markSectionDirty(pos.getY() >> 4);
        }
    }
}
