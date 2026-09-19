package com.inf.farlands.util.maps;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.network.expand.y.ChunkDataPacket;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class Common {
    private static volatile long lastConflictInfo = 0;

    public static void conflict(String kind, long key, int ox, int oy, int oz, int nx, int ny, int nz) {
        long now = System.currentTimeMillis();
        if (now - lastConflictInfo < 1000) {
            return;
        }
        lastConflictInfo = now;
        InfsFarlands.LOGGER.warn("Hash Conflicted! {} key=0x{} old={},{},{} new={},{},{}",
                kind, Long.toHexString(key), ox, oy, oz, nx, ny, nz);
    }

    private record PendingKey(ResourceKey<Level> dimension, long chunkPos) {
    }

    /**
     * §5 缓存结构：chunk 未加载时到达的 section 条目。放在主源集（两端可引用），
     * 施放逻辑在客户端（只用 SectionEntry，不碰 ClientLevel，避免污染 main 源集）。
     */
    public static final class PendingSections {
        public final int minY;
        public final List<ChunkDataPacket.SectionEntry> entries = new ArrayList<>();

        public PendingSections(int minY) {
            this.minY = minY;
        }
    }

    private static final ConcurrentHashMap<PendingKey, PendingSections> PENDING_SECTION_DATA = new ConcurrentHashMap<>();

    /**
     * §5 数据到达但 chunk 未加载 → 缓存，等 chunk 加载后补应用。直接丢弃会导致方块
     * 数据永久缺失（服务端已出队不重发）——表现为空缺/双端不同步。
     *
     * <p>
     * key 含维度：tp 跨维度后旧维度缓存条目不与同坐标新维度冲突。
     */
    public static void cachePendingSectionData(ResourceKey<Level> dimension, int cx, int cz, int minY,
            ChunkDataPacket.SectionEntry entry) {
        PENDING_SECTION_DATA.computeIfAbsent(new PendingKey(dimension, ChunkPos.pack(cx, cz)),
                k -> new PendingSections(minY)).entries.add(entry);
    }

    /** 取出并移除该 chunk 的 §5 缓存；无则 null。 */
    public static PendingSections takePendingSectionData(ResourceKey<Level> dimension, int cx, int cz) {
        return PENDING_SECTION_DATA.remove(new PendingKey(dimension, ChunkPos.pack(cx, cz)));
    }

    /** chunk 卸载时丢弃缓存，防残留堆积。 */
    public static void discardPendingSectionData(ResourceKey<Level> dimension, ChunkPos pos) {
        PENDING_SECTION_DATA.remove(new PendingKey(dimension, pos.pack()));
    }
}
