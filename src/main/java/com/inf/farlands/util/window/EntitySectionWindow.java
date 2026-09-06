package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 实体 section 窗口并集，服务端主线程每 tick 更新。
 */
public final class EntitySectionWindow {

    private EntitySectionWindow() {
    }

    /** 有序不重叠区间 [min0, max0, min1, max1, ...]。 */
    private static volatile int[] ranges = new int[0];

    /** 主线程只读访问窗口并集区间；volatile 引用，读安全。 */
    public static int[] ranges() {
        return ranges;
    }

    public static void update(List<ServerPlayer> players) {
        int n = players.size();
        if (n == 0) {
            ranges = new int[0];
            return;
        }
        int[][] list = new int[n][2];
        for (int i = 0; i < n; i++) {
            ServerPlayer p = players.get(i);
            int c = Mth.floorDiv(p.getBlockY(), 16);
            list[i][0] = c - FarlandsConfig.verticalSimulationDistance;
            list[i][1] = c + FarlandsConfig.verticalSimulationDistance;
        }
        Arrays.sort(list, Comparator.comparingInt(a -> a[0]));
        int[] merged = new int[n * 2];
        int m = 0;
        for (int[] iv : list) {
            if (m == 0) {
                merged[m++] = iv[0];
                merged[m++] = iv[1];
            } else if (iv[0] <= merged[m - 1] + 1) {
                merged[m - 1] = Math.max(merged[m - 1], iv[1]);
            } else {
                merged[m++] = iv[0];
                merged[m++] = iv[1];
            }
        }
        ranges = Arrays.copyOf(merged, m);
    }

    public static boolean inAnyWindow(int sectionY) {
        int[] r = ranges;
        for (int i = 0; i < r.length; i += 2) {
            if (sectionY >= r[i] && sectionY <= r[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否在所有窗口区间及余量 margin 之外。
     */
    public static boolean isOutsideAllWindows(int sectionY, int margin) {
        int[] r = ranges;
        for (int i = 0; i < r.length; i += 2) {
            if (sectionY >= r[i] - margin && sectionY <= r[i + 1] + margin) {
                return false;
            }
        }
        return true;
    }

    /** 遍历并集区间内全部 sectionY，供地形管线触发收集用。区间合并后数量有限。 */
    public static void forEachSectionInAnyWindow(java.util.function.IntConsumer consumer) {
        int[] r = ranges;
        for (int i = 0; i < r.length; i += 2) {
            for (int sy = r[i]; sy <= r[i + 1]; sy++) {
                consumer.accept(sy);
            }
        }
    }
}
