package com.inf.farlands.serialize;

import com.inf.farlands.terrain.pipeline.GenQueue;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * fsa 与 terrain 的接线点。
 *
 * 旧仓库这两处是 GenQueue 的直接调用，本 port 收口到本类，让 fsa 侧只依赖这里：
 *   isChunkBusy 对应 GenQueue.isChunkBusy。生成或光照在途时 fsa 不清理、不卸载写盘，属于保守跳过。
 *   enqueueGen 对应 GenQueue.enqueueChunk 与 GenQueue.enqueue。读回完成后把 chunk 或单个 section
 *               送入生成队列，由 isOrAfter(TERRAIN) 自动跳过已读回的 section。
 */
public final class TerrainHooks {

    private TerrainHooks() {
    }

    public static boolean isChunkBusy(LevelChunk chunk) {
        return GenQueue.isChunkBusy(chunk);
    }

    /** 整 chunk 入生成队列，用于整个 chunk 读回完成。 */
    public static void enqueueGen(LevelChunk chunk) {
        GenQueue.enqueueChunk(chunk);
    }

    /** 单 section 入生成队列，用于窗口滑入时的单 section 读回完成。 */
    public static void enqueueGen(LevelChunk chunk, int sectionY) {
        GenQueue.enqueue(chunk, sectionY);
    }
}
