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
 *   2 TERRAIN      该段已 fill
 *   3 SURFACE      地表已应用
 *   4 CARVERS      雕刻已完成，地物未放
 *   5 DECORATED    该 chunk 作为中心的那一遍装饰已跑完
 *   6 LIGHTED      光照已完成
 *
 * <p>不变式：装饰先于点亮，即 LIGHTED 蕴含 DECORATED。fsa 只写 stage >= LIGHTED 的段，所以
 * DECORATED 只活在内存里，磁盘上不会出现它。
 *
 * <p>载体：chunkPos 到 sectionY 到 stage 的无装箱分段 map。本 port 不用 Fabric API，stage 由
 * fsa 自己承载，全 port 只有这一份状态。
 *
 * 推进点。BIOMES 在 BiomeFiller，TERRAIN 在 GenTask 的 fill 之后，SURFACE 在 SurfaceFiller，
 * CARVERS 在 CarverFiller，DECORATED 在 DecorationFiller 的收尾，LIGHTED 在
 * GenQueue.triggerLight 的 whenComplete 里由 promoteAllGenToLighted 一次升段。
 *
 * <p>光照会升的三档由 {@link #isAwaitingLight(int)} 一处给出：promoteAllGenToLighted、
 * hasAwaitingLight 与 GenTask 的补触发判据都走它，避免三处各写一份而失同步。CARVERS 不在其中，
 * 它的出路是装饰；若光照回调把它一并升 LIGHTED，未装饰的段会被标成完成并落盘。
 *
 * 生命周期。chunk 卸载时由 SectionLifecycle.flushChunk 在编码完成后 clear，内存有界。
 * 并发。写点在主线程、genPool 或 lightPool，值容器是 ConcurrentHashMap，读点 encodeNow
 * 可能跑在 farlands-encode 池线程，与之并发安全。
 */
public final class SectionStage {

    public static final int UNPROCESSED = 0;
    public static final int BIOMES = 1;
    public static final int TERRAIN = 2;
    public static final int SURFACE = 3;
    public static final int CARVERS = 4;
    public static final int DECORATED = 5;
    public static final int LIGHTED = 6;

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
     * 光照会升的三档：一次光照跑完，这三档的段都能升到 LIGHTED。CARVERS 不在其中，它的出路是
     * 装饰；把它算进来会让光照回调升完段后仍判为还有活，无限续接下一轮。
     */
    public static boolean isAwaitingLight(int stage) {
        return stage == TERRAIN || stage == SURFACE || stage == DECORATED;
    }

    /**
     * 光照完成回调：该 chunk 全部在 {@link #isAwaitingLight(int)} 里的段升 LIGHTED。光照覆盖
     * 全 chunk，含已地表、已雕刻与已装饰的 section，CHM.replaceAll 线程安全。
     */
    public static void promoteAllGenToLighted(LevelChunk chunk) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m != null) {
            m.replaceAll((k, v) -> isAwaitingLight(v) ? LIGHTED : v);
        }
    }

    /**
     * 该 chunk 是否还有光照能升、但还没升的 section。光照完成后再检查，驱动下一批。
     *
     * <p>判据与 {@link #promoteAllGenToLighted} 的匹配面同源，两处都由 {@link #isAwaitingLight(int)}
     * 给出。放宽到全体 belowLighted 会把 CARVERS 也算进来，回调因此永远为真。
     */
    public static boolean hasAwaitingLight(LevelChunk chunk) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m == null) {
            return false;
        }
        for (int v : m.values()) {
            if (isAwaitingLight(v)) {
                return true;
            }
        }
        return false;
    }

    /** 段是否做过但没点亮，即 stage 落在 TERRAIN 到 LIGHTED 之间，含 DECORATED。 */
    public static boolean isBelowLighted(int stage) {
        return stage >= TERRAIN && stage < LIGHTED;
    }

    /** 停服时清空全部 chunk 的阶段表。键只含坐标，不清会让下一个世界同坐标的 chunk 继承旧阶段。 */
    public static void clearAll() {
        STAGES.clear();
    }

    /**
     * 该 chunk 是否有做过但没点亮的段。fsa 读回后停在 CARVERS 的段属这一类，它是光照补触发与
     * 数据就绪判据共同的输入。
     *
     * <p>扫描路径也要带上这条判据：一个 stage 停在 CARVERS 的 chunk 没有任何 pending 步骤，
     * 光照失败一次就再没有下一次，它的段也永远不会写盘。
     */
    public static boolean hasBelowLighted(LevelChunk chunk) {
        boolean[] found = { false };
        forEachStage(chunk, (sy, stage) -> {
            if (isBelowLighted(stage)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** 遍历该 chunk 的 section 状态。无 key 时什么都不做。 */
    public static void forEachStage(LevelChunk chunk, BiConsumer<Integer, Integer> consumer) {
        ConcurrentHashMap<Integer, Integer> m = STAGES.get(chunk.getPos().pack());
        if (m != null) {
            m.forEach(consumer);
        }
    }
}
