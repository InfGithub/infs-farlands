package com.inf.farlands.light;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.decorationFiller.DecorationClaim;
import com.inf.farlands.util.map.Long2ObjectStripedMap;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * per-chunk 光照任务锁，半径 2。
 *
 * <p>一个 chunk 的传播会写邻居 section，方块光最远 15 格即 XZ ±1 section，且
 * 天空光初始化与空 section 检查涉 3×3 邻居，锁半径 2 保证相邻 chunk 的光照任务
 * 不并发，同一 section 不被两个任务同时写，DataLayer.set 半字节读改写不竞争。
 *
 * <p>获取按 chunk key 排序以防死锁；任一失败回滚已获取的锁并返回 0，调用方
 * 将任务放回队列重试。
 *
 * <p>另一类失败是装饰：装饰在池上写段与天光光源列，而光照任务读同一批段的
 * 光源列。判据并进这一把锁的两侧，见 tryLock 与 FarLandsLightEngine 的
 * tryLockDomain：光照侧遇到被认领的格即让路，装饰侧直接取这把锁，两个方向都走
 * 失败即重试，没有等待，因此也没有锁序环。
 *
 * <p><b>取域返回持有者编号，放域按编号 CAS。</b>每格是 {@code AtomicLong}，0 表示空闲，
 * 非 0 是该次取域的编号。取域成功返回编号，失败返回 0；放域必须把编号带回来，只清属于自己的格。
 * 旧写法是无条件清零，两方交错时会放掉别人刚取的格。
 */
public final class LightTaskLock {

    public static final int RADIUS = 2;
    private static final int KEY_COUNT = (RADIUS * 2 + 1) * (RADIUS * 2 + 1); // 25

    /** 本锁属于哪个维度。装饰认领按维度分表，判据要用同一个键。 */
    private final ResourceKey<Level> dimension;

    /** 无装箱分段 map，key 为 ChunkPos.pack，相邻 chunk 位布局低位聚集，CHM 会树化。 */
    private final Long2ObjectStripedMap<AtomicLong> locks = new Long2ObjectStripedMap<>(1 << 8);

    /**
     * key 缓冲，光照任务侧复用：那里的热路径是 drainLight 单例调用，consumerActive CAS 保证同一时刻
     * 至多一个它，所以缓冲无并发。省掉每次取域的 new long[25] 分配。
     *
     * <p>装饰侧不用它，见 {@link #tryLockDomain}。
     */
    private final long[] keyBuf = new long[KEY_COUNT];

    /** 取域编号，全局递增。每次尝试领一个，成功的那次覆盖它取的 25 格。 */
    private static final AtomicLong OWNER_SEQ = new AtomicLong();

    /** 失配日志限流，成例见 SectionLifecycle 的 ENCODE_FAIL_LOGGED。 */
    private static final AtomicLong MISMATCH_LOGGED = new AtomicLong();

    public LightTaskLock(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    /**
     * 光照任务侧取锁：中心 chunk 半径 {@link #RADIUS} 内全部锁。只在 drainLight 单例里调用。
     *
     * @return 持有者编号，0 表示失败
     */
    public long tryLock(int chunkX, int chunkZ) {
        return this.tryLock(chunkX, chunkZ, this.keyBuf, false);
    }

    /**
     * 装饰侧取锁。与 {@link #tryLock} 是同一把锁，三处不同：
     *
     * <p>一，每次调用自备一个局部缓冲。装饰在 farlands-gen 上跑，两个装饰会在两个池线程上并发调
     * 它，复用 {@link #keyBuf} 会互相覆盖 key；回滚按下标放锁，读到被改过的值就会放错格，并把它已占
     * 的格永久留下。一次装饰一次分配，可忽略。
     *
     * <p>二，豁免自己那九格认领。本入口只许在 {@link DecorationClaim#tryClaim} 成功、且尚未释放
     * 期间调用：那时中心 ±1 的写域必定是自己占的，而认领表也在本锁的获取路径上，不豁免就是装饰
     * 拿自己的认领挡自己，症状是 tick() 永远提交不出去、预加载停在同一个格数上。豁免范围正好是本
     * 锁域的内层 3×3，别人的认领照旧让路，哪怕只差一格。
     *
     * <p>三，失败即放弃本次，此时一个方块都没写，调用方留表下一轮，不等待。
     *
     * @return 持有者编号，0 表示失败
     */
    public long tryLockDomain(int chunkX, int chunkZ) {
        return this.tryLock(chunkX, chunkZ, new long[KEY_COUNT], true);
    }

    private long tryLock(int chunkX, int chunkZ, long[] keys, boolean exemptOwnWriteDomain) {
        int n = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                keys[n++] = ChunkPos.pack(chunkX + dx, chunkZ + dz);
            }
        }
        Arrays.sort(keys, 0, n);
        long owner = OWNER_SEQ.incrementAndGet();
        int acquired = 0;
        for (int i = 0; i < n; i++) {
            // 装饰正在写这一片的段与天光光源列：本轮让路，回滚已取锁，调用方重排。
            if (DecorationClaim.isClaimed(this.dimension, keys[i])
                    && !(exemptOwnWriteDomain && isOwnWriteDomain(keys[i], chunkX, chunkZ))) {
                this.rollback(keys, acquired, owner);
                return 0L;
            }
            AtomicLong lock = locks.computeIfAbsent(keys[i], k -> new AtomicLong());
            if (lock.compareAndSet(0L, owner)) {
                acquired++;
            } else {
                this.rollback(keys, acquired, owner);
                return 0L;
            }
        }
        return owner;
    }

    /**
     * 回滚已获取的前 acquired 个锁。按持有者 CAS，失败即说明该格已被别人取走，记一次失配。
     */
    private void rollback(long[] keys, int acquired, long owner) {
        for (int j = 0; j < acquired; j++) {
            AtomicLong held = locks.get(keys[j]);
            if (held != null) {
                release(held, owner, keys[j]);
            }
        }
    }

    /** 该格是否落在中心的 ±1 写域内，即装饰自己刚认领的那九格。 */
    private static boolean isOwnWriteDomain(long key, int centerX, int centerZ) {
        int dx = Math.abs(ChunkPos.getX(key) - centerX);
        int dz = Math.abs(ChunkPos.getZ(key) - centerZ);
        return dx <= DecorationClaim.WRITE_RADIUS && dz <= DecorationClaim.WRITE_RADIUS;
    }

    /**
     * 释放中心 chunk 半径内的锁，只清 {@code owner} 名下的格。
     *
     * <p>调用方必须把取域时拿到的编号带回来。装饰侧由 {@code region != null} 保证取过锁，光照侧由
     * 调度循环的配对保证。
     */
    public void unlock(int chunkX, int chunkZ, long owner) {
        if (owner == 0L) {
            return;
        }
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                AtomicLong lock = locks.get(key);
                if (lock != null) {
                    release(lock, owner, key);
                }
            }
        }
    }

    /** 清格。CAS 失败即说明这一格已不属于本次持有者，按限流打成一条日志。 */
    private static void release(AtomicLong lock, long owner, long key) {
        if (lock.compareAndSet(owner, 0L)) {
            return;
        }
        if (MISMATCH_LOGGED.getAndIncrement() < 20) {
            InfsFarlands.LOGGER.info("LKLOCK mismatch key={},{} owner={} cur={}",
                    ChunkPos.getX(key), ChunkPos.getZ(key), owner, lock.get());
        }
    }
}
