package com.inf.farlands.light;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * 单层光照存储，替代 vanilla 的 queued/updating/visible 三层缓冲 + swap + COW。
 * 每引擎一个 {@code Long2ObjectStripedMap<DataLayer>}，即无装箱分段 map
 * 传播热路径 CHM Long key 装箱是 young GC 主源之一。
 *
 * <p>
 * 分段锁：所有操作锁内，每段互斥；与旧 CHM 的弱一致语义不同——读写严格
 * 串行于段内，无瞬时快照，一致更强。
 */
public class FarLandsDataLayerStorage {
    private final Long2ObjectStripedMap<DataLayer> map = new Long2ObjectStripedMap<>(1 << 16);

    /**
     * chunkKey → 该 chunk 的 section key 集合，removeChunk 用 O(k) 替代全遍历。
     * StripedMap 替代 CHM&lt;Long,...&gt;，消除建层热路径 computeIfAbsent 的 Long
     * 装箱。返回 set 引用后锁外 synchronized(set) 操作，与 CHM 语义一致。
     */
    private final Long2ObjectStripedMap<LongOpenHashSet> chunkSections = new Long2ObjectStripedMap<>(1 << 14);

    public DataLayer get(long key) {
        return map.get(key);
    }

    public DataLayer getOrCreate(long key, long chunkKey) {
        DataLayer dl = map.computeIfAbsent(key, k -> new DataLayer());
        indexAdd(chunkKey, key);
        return dl;
    }

    public void put(long key, DataLayer layer, long chunkKey) {
        map.put(key, layer);
        indexAdd(chunkKey, key);
    }

    public DataLayer remove(long key, long chunkKey) {
        DataLayer dl = map.remove(key);
        LongOpenHashSet set = chunkSections.get(chunkKey);
        if (set != null) {
            synchronized (set) {
                set.remove(key);
                if (set.isEmpty()) {
                    chunkSections.remove(chunkKey);
                }
            }
        }
        return dl;
    }

    public boolean containsKey(long key) {
        return map.containsKey(key);
    }

    /** 移除一个 chunk 的全部光照层，O(sections/chunk)，替代全遍历。 */
    public void removeChunk(long chunkKey) {
        LongOpenHashSet set = chunkSections.remove(chunkKey);
        if (set != null) {
            synchronized (set) {
                for (long k : set) {
                    map.remove(k);
                }
            }
        }
    }

    private void indexAdd(long chunkKey, long key) {
        LongOpenHashSet set = chunkSections.computeIfAbsent(chunkKey, k -> new LongOpenHashSet());
        synchronized (set) {
            set.add(key);
        }
    }
}
