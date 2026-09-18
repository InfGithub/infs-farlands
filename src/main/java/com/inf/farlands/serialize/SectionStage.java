package com.inf.farlands.serialize;

import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * per-section 生成阶段，fsa 的持久化字段之一，写进 fsa 条目并在读回时恢复。
 *
 * 取值由用户指定，顺序单值，比较用 {@code >=} ：
 *   0 UNPROCESSED  未处理
 *   1 NOISE        该段已 fill
 *   2 LIGHTED      光照已完成
 *
 * 载体。旧仓库在 NeoForge 1.21.1 上用 attachment 挂在 ChunkAccess 上，类型是
 * AttachmentType 与 IAttachmentSerializer，NBT 形如 list of {y, stage}，并且与 terrain 的
 * GenQueue 与 FarLandsGenState 共用，属于 fsa 与 terrain 的共享数据面。本 port 不用
 * Fabric API，且 terrain 未移植，因此 stage 改由 fsa 自己承载，即 chunkPos 到 sectionY
 * 到 stage 的无装箱分段 map。terrain 将来移植时，这里应换回挂在 chunk 上的共享载体，
 * 而不是另起一份状态。
 *
 * 当前无人推进 stage。置 NOISE 的点在 terrain 的 fill，旧 GenTask。置 LIGHTED 的点在
 * GenQueue.triggerLight 的 whenComplete，即 promoteAllGenToLighted。两者都随 terrain
 * 缺席。因此本 port 内除从磁盘读回的值以外，stage 恒为 UNPROCESSED。这是不实现 terrain
 * 的直接后果，已确认接受。读写链路完整，terrain 移植后自然接上。
 *
 * 生命周期。chunk 卸载时由 SectionLifecycle.flushChunk 在编码完成后 clear，内存有界。
 * 并发。写点在主线程或编码路径，值容器是 ConcurrentHashMap，读点 encodeNow 可能跑在
 * farlands-encode 池线程，与之并发安全。
 */
public final class SectionStage {

    public static final int UNPROCESSED = 0;
    public static final int NOISE = 1;
    public static final int LIGHTED = 2;

    private static final Long2ObjectStripedMap<ConcurrentHashMap<Integer, Integer>> STAGES =
            new Long2ObjectStripedMap<>(1 << 12);

    private SectionStage() {
    }

    private static ConcurrentHashMap<Integer, Integer> map(long chunkKey) {
        return STAGES.computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
    }

    public static int getStage(LevelChunk chunk, int sectionY) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        return m == null ? UNPROCESSED : m.getOrDefault(sectionY, UNPROCESSED);
    }

    public static void setStage(LevelChunk chunk, int sectionY, int stage) {
        map(chunk.getPos().pack()).put(sectionY, stage);
    }

    /** 某阶段是否已做。无 key 时按 UNPROCESSED。 */
    public static boolean isOrAfter(LevelChunk chunk, int sectionY, int stage) {
        return getStage(chunk, sectionY) >= stage;
    }

    /** 删除某 section 的状态。fsa 清理时状态已随 section 落盘，载体同步删除。 */
    public static void removeStage(LevelChunk chunk, int sectionY) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m != null) {
            m.remove(sectionY);
        }
    }

    /** chunk 卸载后整条清空，防随探索单调增长。 */
    public static void clear(LevelChunk chunk) {
        STAGES.remove(chunk.getPos().pack());
    }
}
