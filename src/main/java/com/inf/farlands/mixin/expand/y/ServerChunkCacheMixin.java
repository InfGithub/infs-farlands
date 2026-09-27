package com.inf.farlands.mixin.expand.y;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.SavedDataStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    private ThreadedLevelLightEngine lightEngine;

    @Shadow
    private SavedDataStorage savedDataStorage;

    @Shadow
    public ChunkMap chunkMap;

    @Shadow
    public void save(boolean flush) {
    }

    @Overwrite
    public void close() throws IOException {
        try {
            this.save(true);
        } finally {
            this.savedDataStorage.close();
            this.lightEngine.close();
            this.chunkMap.close();
        }
    }

    /**
     * 入场期的出生区等待不跟数据走。
     *
     * <p>addTicketAndLoadWithRadius 取的是 getChunkRangeFuture 的 FULL 范围，而 PrepareSpawnTask 与
     * PlayerSpawnFinder 在玩家入场之前就等它，此时没有任何玩家窗口，数据就绪永远不成立。模板里 FULL
     * 本来是立即成功的，所以这里直接给一个已完成的 future，等价于改动前的时点。
     */
    @Redirect(method = "addTicketAndLoadWithRadius", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getChunkRangeFuture(Lnet/minecraft/server/level/ChunkHolder;ILjava/util/function/IntFunction;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<List<ChunkAccess>>> farlands$skipDataWait(ChunkMap chunkMap,
            ChunkHolder chunkHolder, int radius, IntFunction<ChunkStatus> distanceToStatus) {
        return CompletableFuture.completedFuture(ChunkResult.of(List.<ChunkAccess>of()));
    }
}