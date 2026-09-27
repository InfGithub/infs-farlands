package com.inf.farlands.mixin.terrain.pipeline;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.level.ChunkHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 摘掉 promotion future 的存盘依赖。
 *
 * <p>promotion 挂到数据就绪上之后，saveSync 若继续等 ticking 与 entityTicking 两个 future，
 * 空场 forceload 这类永远没有窗口的 chunk 会既写不了 chunk NBT 也卸载不了。今天两个 future 都是
 * 立即成功，saveSync 本来立即完成，所以摘掉是抵消，不是新语义。
 *
 * <p>只针对 updateFutures 里的调用点：GenerationChunkHolder.increaseGenerationRefCount 的
 * generationSaveSyncFuture 走同一个方法，但不在本方法的注入范围内。卸载等待仍由 saveSync 承担。
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderSaveSyncMixin {

    @Redirect(method = "updateFutures", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;addSaveDependency(Ljava/util/concurrent/CompletableFuture;)V"))
    private void farlands$skipPromotionSaveDependency(ChunkHolder holder, CompletableFuture<?> sync) {
        // 丢弃 promotion future 的存盘依赖
    }
}
