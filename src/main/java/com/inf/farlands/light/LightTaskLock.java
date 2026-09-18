package com.inf.farlands.light;

import com.inf.farlands.util.map.Long2ObjectStripedMap;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.level.ChunkPos;

/**
 * per-chunk 光照任务锁，半径 2。
 *
 * 一个 chunk 的传播会写邻居 section，方块光最远 15 格即 XZ ±1 section，且
 * 天空光初始化/空 section 检查涉 3×3 邻居 锁半径 2 保证相邻 chunk 的光照任务
 * 不并发，同一 section 不被两个任务同时写，DataLayer.set 半字节读改写不竞争。
 *
 * 获取按 chunk key 排序以防死锁；任一失败回滚已获取的锁并返回 false，调用方
 * 将任务放回队列重试。
 */
public final class LightTaskLock {

    public static final int RADIUS = 2;
    private static final int KEY_COUNT = (RADIUS * 2 + 1) * (RADIUS * 2 + 1); // 25

    /** 无装箱分段 map，key 为 ChunkPos.pack，相邻 chunk 位布局低位聚集，CHM 会树化。 */
    private final Long2ObjectStripedMap<AtomicBoolean> locks = new Long2ObjectStripedMap<>(1 << 8);

    /**
     * key 缓冲复用为实例字段：tryLock 只在 drainLight 单例调用，consumerActive CAS
     * 保证同一时刻至多一个 tryLock，缓冲无并发。消除每次 tryLock 的 new long[25]
     * 分配，JFR 55G，传播任务调度热路径。
     */
    private final long[] keyBuf = new long[KEY_COUNT];

    /** 尝试获取中心 chunk 半径 {@link #RADIUS} 内全部锁。 */
    public boolean tryLock(int chunkX, int chunkZ) {
        long[] keys = this.keyBuf;
        int n = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                keys[n++] = ChunkPos.pack(chunkX + dx, chunkZ + dz);
            }
        }
        Arrays.sort(keys, 0, n);
        int acquired = 0;
        for (int i = 0; i < n; i++) {
            AtomicBoolean lock = locks.computeIfAbsent(keys[i], k -> new AtomicBoolean());
            if (lock.compareAndSet(false, true)) {
                acquired++;
            } else {
                // 回滚已获取的
                for (int j = 0; j < acquired; j++) {
                    AtomicBoolean held = locks.get(keys[j]);
                    if (held != null) {
                        held.set(false);
                    }
                }
                return false;
            }
        }
        return true;
    }

    /** 释放中心 chunk 半径内全部锁。 */
    public void unlock(int chunkX, int chunkZ) {
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                AtomicBoolean lock = locks.get(key);
                if (lock != null) {
                    lock.set(false);
                }
            }
        }
    }
}
