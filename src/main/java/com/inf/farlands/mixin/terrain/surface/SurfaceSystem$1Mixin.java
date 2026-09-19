package com.inf.farlands.mixin.terrain.surface;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 地表阶段的写方块改为直写 section，绕开 ChunkAccess.setBlockState 的副作用。
 *
 * 背景：surface 与 fill 都跑在 genPool 线程上，但 fill 走 section.setBlockState(..., false)
 * 直写，surface 走 vanilla SurfaceSystem 的 BlockColumn，最终落到
 * ChunkAccess.setBlockState。
 * 后者有 vanilla 该有的副作用链：LevelChunk.setBlockState -> onPlace -> scheduleTick。于是
 * genPool 线程会去写 LevelChunkTicks 的 PriorityQueue，而服务端线程同时在 LevelTicks.tick
 * 里 poll 同一个队列。PriorityQueue 非线程安全，两线程交错使堆的 size 与数组失去自洽，
 * poll 随后把空槽当元素解引用，抛
 * NullPointerException: Cannot read field "triggerTick" because "o1" is null。
 *
 * 本重定向只去掉那次 setBlockState 调用，取值仍是原版逻辑：同一 section、同一局部坐标、同一
 * state，只是用无锁直写。副作用一并消失：onPlace、scheduleTick、markAndNotifyBlock、
 * neighborChanged 都不再触发。与 fill 路径一致，两阶段同 chunk 串行，不引入新的竞争。
 *
 * 调用方对返回值不感兴趣，原字节码在调用后立即 pop，因此返回 null 安全。
 * 里层 BlockColumn.setBlock 的 y 是绝对 block Y，调用点之前已 mpos.setY(y)，所以用
 * pos.getY() 取该值，索引语义与原版一致。
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceSystem$1")
public class SurfaceSystem$1Mixin {

    @Redirect(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState farlands$directSectionWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        LevelChunkSection section = chunk.getSection(
                ((LevelHeightAccessor) ((WindowedChunk) chunk).levelHeightAccessor()).getSectionIndex(pos.getY()));
        if (section != null) {
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        }
        return null;
    }
}
