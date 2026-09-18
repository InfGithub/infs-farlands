package com.inf.farlands.serialize;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * fsa 与 terrain 的接线点，当前是空实现。用户指令是不实现 terrain，因此 fsa 里与 terrain
 * 相接的两处调用用最小载体顶过去，只要求编译通过。
 *
 * 旧仓库这两处的真实实现是：
 *   isChunkBusy   对应 GenQueue.isChunkBusy(lc)。生成或光照在途时 fsa 不清理、不卸载
 *                 写盘，属于保守跳过。
 *   enqueueGen    对应 GenQueue.enqueueChunk(lc)。读回完成后把 chunk 送入生成队列，由
 *                 isOrAfter(NOISE) 自动跳过已读回的 section。
 *
 * 空实现的语义后果，只在 terrain 缺席时成立：
 *   isChunkBusy 恒 false，于是 fsa 永远认为没有生成或光照在途，清理与卸载写盘不再被
 *   terrain 在途状态挡住。
 *   enqueueGen 恒 no-op，于是读回完成的 chunk 不会被送去生成，而由于没有 terrain，
 *   本来也无生成可做。
 *
 * terrain 移植时，把本类两个方法体换成对应的 GenQueue 调用即可，调用点 SectionLifecycle
 * 无需改动。
 */
public final class TerrainHooks {

    private TerrainHooks() {
    }

    /** 旧实现：GenQueue.isChunkBusy(lc)。 */
    public static boolean isChunkBusy(LevelChunk chunk) {
        return false;
    }

    /** 旧实现：GenQueue.enqueueChunk(lc)。 */
    public static void enqueueGen(LevelChunk chunk) {
    }
}
