package com.inf.farlands.mixin.serialize;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 写方块的段级入口：调色板写入与编码池互斥，并给 fsa 标脏。
 *
 * <p>写入侧：普通方块写最终都落到 setBlockState 里那一次 LevelChunkSection.setBlockState
 * 调用，它走带线程检测的调色板重载，而 fsa 编码在 farlands-encode 池上打包同一个容器，
 * 两者跨线程相遇即 PalettedContainer 抛异常。所以这一次调用要在 SectionSerializer.packLockFor
 * 内做，键与另外四个打包点相同。锁只包这一次调用，不含本方法后面的高度图与光照。
 *
 * <p>标脏侧：只在修改实际发生时标脏，判据是返回值非 null，vanilla 在 blockstate 等于 state
 * 的分支返回 null 表示没变。双端共享类，客户端标脏无害，SectionLifecycle 是纯服务端，
 * 客户端从不查询。
 *
 * <p>26.1.2 的签名是 setBlockState(BlockPos, BlockState, int flags)，已 javap 核实。另一处标脏点是
 * terrain 的 fill，GenTask 逐段 fill 与 surface、carvers 升段后标脏。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    /**
     * 调色板写入纳入打包锁。
     *
     * <p>形参表是重定向调用的接收者加实参，接收者是 LevelChunkSection；本类的方法是实例方法，
     * 因此 this 就是 LevelChunk，锁键从它取。
     */
    @Redirect(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState farlands$writeUnderPackLock(LevelChunkSection section, int localX, int localY, int localZ,
            BlockState state) {
        synchronized (SectionSerializer.packLockFor(((LevelChunk) (Object) this).getPos().pack())) {
            return section.setBlockState(localX, localY, localZ, state);
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void farlands$markSectionDirty(BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            ((WindowedChunk) this).markSectionDirty(pos.getY() >> 4);
        }
    }
}
