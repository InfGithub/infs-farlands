package com.inf.farlands.terrain.decorationFiller;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 装饰写域认领表：一次装饰占住中心与八个邻居，占住的 chunk 在此期间不再被第二次装饰认领，
 * 也不被编码与下发读，两处都经 {@code GenQueue.isChunkBusy}。
 *
 * <p>认领兼作在途去重：中心自己也在九格里，所以同一个 chunk 在收尾释放之前不可能被再次认领，
 * 不需要另立一张「已提交」集合。
 *
 * <p>按维度分表：chunk 坐标在两个维度里是同一个数，而装饰在主世界与下界都会发生，共一张表会让
 * 一个维度的认领挡住另一个维度。
 *
 * <p>键用 {@link ChunkPos#pack(int, int)}，与地形侧同一套坐标口径，不是 section 口径。
 */
public final class DecorationClaim {

    /** 写域半径。与 vanilla FEATURES 步的 blockStateWriteRadius(1) 同源。 */
    public static final int WRITE_RADIUS = 1;

    /** 写域格数，扫描顺序固定：dx 外层、dz 内层。 */
    private static final int CELLS = (WRITE_RADIUS * 2 + 1) * (WRITE_RADIUS * 2 + 1);

    /** 维度到该维度已占的 chunk 键。 */
    private static final Map<ResourceKey<Level>, Set<Long>> CLAIMED = new ConcurrentHashMap<>();

    private DecorationClaim() {
    }

    /** 该维度该 chunk 是否已被某次装饰的写域覆盖。编码与下发侧的判据。 */
    public static boolean isClaimed(ResourceKey<Level> dimension, long chunkKey) {
        Set<Long> claimed = CLAIMED.get(dimension);
        return claimed != null && claimed.contains(chunkKey);
    }

    /**
     * 尝试在该维度以该 chunk 为中心认领整个写域。九格全部空闲才成功；任一格已被占则回滚已占的
     * 前缀并失败，不留下部分占用。
     *
     * @return 成功返回真；调用方成功后必须在收尾里调 {@link #release}
     */
    public static boolean tryClaim(ResourceKey<Level> dimension, int centerX, int centerZ) {
        Set<Long> claimed = CLAIMED.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet());
        int filled = 0;
        for (int cell = 0; cell < CELLS; cell++) {
            if (!claimed.add(keyAt(centerX, centerZ, cell))) {
                // 只回滚前 filled 格，那是本次已占的全部；扫描顺序固定，所以前缀是确定的。
                for (int done = 0; done < filled; done++) {
                    claimed.remove(keyAt(centerX, centerZ, done));
                }
                return false;
            }
            filled++;
        }
        return true;
    }

    /** 释放该中心认领的整个写域。 */
    public static void release(ResourceKey<Level> dimension, int centerX, int centerZ) {
        Set<Long> claimed = CLAIMED.get(dimension);
        if (claimed == null) {
            return;
        }
        for (int cell = 0; cell < CELLS; cell++) {
            claimed.remove(keyAt(centerX, centerZ, cell));
        }
    }

    /** 停服时清空。不清会让下一个世界同坐标的 chunk 继承旧认领。 */
    public static void clearWorldState() {
        CLAIMED.clear();
    }

    /** 扫描序号到 chunk 键。分解顺序与 {@link #tryClaim} 的循环同序：dx 外层、dz 内层。 */
    private static long keyAt(int centerX, int centerZ, int cell) {
        int span = WRITE_RADIUS * 2 + 1;
        int dx = cell / span - WRITE_RADIUS;
        int dz = cell % span - WRITE_RADIUS;
        return ChunkPos.pack(centerX + dx, centerZ + dz);
    }
}
