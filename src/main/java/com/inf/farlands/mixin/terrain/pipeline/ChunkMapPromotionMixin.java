package com.inf.farlands.mixin.terrain.pipeline;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import com.inf.farlands.serialize.ChunkReadiness;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 BLOCK_TICKING 与 ENTITY_TICKING 两个 promotion future 挂到数据就绪上。
 *
 * <p>模板把全部 chunk 状态一次性置成功，promotion 的门因此开在数据之前：实体可见性、计划刻、
 * 刷怪、随机刻与块包都会先于 fsa 读回与光照发生。这里只改这两个 future 的取数，
 * prepareAccessibleChunk 不动，它的消费者只有刷怪计数与调试串。
 *
 * <p>注入点必须是内层 getChunkRangeFuture：prepareTickingChunk 的 thenApplyAsync 里挂着
 * postProcessGeneration、startTickingChunk 与 onChunkReadyToSend，包在外层拦不住它们。
 *
 * <p>就绪 future 可能先于本调用挂起，也可能在之后才成立，两种都由 ChunkReadiness.drive 每 tick
 * 收敛。这里只把两个 future 串起来，不改 holder 的票级语义。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapPromotionMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    abstract CompletableFuture<ChunkResult<List<ChunkAccess>>> getChunkRangeFuture(ChunkHolder centerChunk,
            int range, IntFunction<ChunkStatus> distanceToStatus);

    @Redirect(method = "prepareTickingChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> farlands$gateTicking(ChunkMap chunkMap,
            ChunkHolder centerChunk, int range, IntFunction<ChunkStatus> distanceToStatus) {
        return farlands$gateOnReady(centerChunk, this.getChunkRangeFuture(centerChunk, range, distanceToStatus));
    }

    @Redirect(method = "prepareEntityTickingChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> farlands$gateEntityTicking(ChunkMap chunkMap,
            ChunkHolder centerChunk, int range, IntFunction<ChunkStatus> distanceToStatus) {
        return farlands$gateOnReady(centerChunk, this.getChunkRangeFuture(centerChunk, range, distanceToStatus));
    }

    /** 与数据就绪组合。就绪 future 在主线程由 drive 完成，随后 thenApplyAsync 仍在主线程执行。 */
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> farlands$gateOnReady(ChunkHolder centerChunk,
            CompletableFuture<ChunkResult<List<ChunkAccess>>> range) {
        return range.thenCombine(ChunkReadiness.whenReady(this.level, centerChunk.getPos()),
                (result, ignored) -> result);
    }
}
