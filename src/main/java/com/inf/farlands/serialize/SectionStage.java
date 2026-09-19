package com.inf.farlands.serialize;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * per-section 生成阶段，fsa 的持久化字段之一，写进 fsa 条目并在读回时恢复。
 *
 * 取值顺序单值，即依赖顺序，比较用 {@code >=} ：
 *   0 UNPROCESSED  未处理
 *   1 BIOMES       群系已填
 *   2 NOISE        该段已 fill
 *   3 SURFACE      地表已应用
 *   4 CARVERS      雕刻已完成
 *   5 LIGHTED      光照已完成
 *
 * 载体。旧仓库在 NeoForge 1.21.1 上用 attachment 挂在 ChunkAccess 上，与 terrain 的
 * GenQueue 共用，属于 fsa 与 terrain 的共享数据面。本 port 不用 Fabric API，stage 由 fsa
 * 自己承载，即 chunkPos 到 sectionY 到 stage 的无装箱分段 map，全 port 只有这一份状态。
 *
 * 推进点。BIOMES 在 BiomeFiller，NOISE 在 GenTask 的 fill 之后，SURFACE 在 SurfaceFiller，
 * CARVERS 在 CarverFiller，LIGHTED 在 GenQueue.triggerLight 的 whenComplete 里由
 * promoteAllGenToLighted 一次升段。
 *
 * 生命周期。chunk 卸载时由 SectionLifecycle.flushChunk 在编码完成后 clear，内存有界。
 * 并发。写点在主线程、genPool 或 lightPool，值容器是 ConcurrentHashMap，读点 encodeNow
 * 可能跑在 farlands-encode 池线程，与之并发安全。
 */
public final class SectionStage {

    public static final int UNPROCESSED = 0;
    public static final int BIOMES = 1;
    public static final int NOISE = 2;
    public static final int SURFACE = 3;
    public static final int CARVERS = 4;
    public static final int LIGHTED = 5;

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

    /**
     * 光照完成回调：该 chunk 全部 NOISE、SURFACE、CARVERS 升 LIGHTED。光照覆盖全 chunk，
     * 含已地表与雕刻处理的 section，CHM.replaceAll 线程安全。
     */
    public static void promoteAllGenToLighted(LevelChunk chunk) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m != null) {
            m.replaceAll((k, v) -> (v == NOISE || v == SURFACE || v == CARVERS) ? LIGHTED : v);
        }
    }

    /** 该 chunk 是否还有 NOISE 未 LIGHTED 的 section。光照完成后再检查，驱动下一批。 */
    public static boolean hasAnyGen(LevelChunk chunk) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m == null) {
            return false;
        }
        for (int v : m.values()) {
            if (v == NOISE) {
                return true;
            }
        }
        return false;
    }

    /** 遍历该 chunk 的 section 状态。无 key 时什么都不做。 */
    public static void forEachStage(LevelChunk chunk, BiConsumer<Integer, Integer> consumer) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m != null) {
            m.forEach(consumer);
        }
    }
}
