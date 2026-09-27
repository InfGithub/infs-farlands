package com.inf.farlands.util.maps;

import com.inf.farlands.util.map.Long2ObjectStripedMap;
import com.inf.farlands.util.pos.IntBlockPos;

public class BlockUtil {
    private static volatile Long2ObjectStripedMap<IntBlockPos> lookup = new Long2ObjectStripedMap<>(1 << 23);
    private static volatile Long2ObjectStripedMap<IntBlockPos> oldLookup;

    public static void put(long key, int x, int y, int z) {
        IntBlockPos prev = lookup.get(key);
        if (prev != null) {
            if (prev.x != x || prev.y != y || prev.z != z) {
                Common.conflict("block", key, prev.x, prev.y, prev.z, x, y, z);
            }
            return;
        }
        IntBlockPos np = new IntBlockPos(x, y, z);
        prev = lookup.putIfAbsent(key, np);
        if (prev != null) {
            // 竞态，并发注册，np 成垃圾
            if (prev.x != x || prev.y != y || prev.z != z) {
                Common.conflict("block", key, prev.x, prev.y, prev.z, x, y, z);
            }
        }
    }

    public static IntBlockPos get(long key) {
        IntBlockPos bp = lookup.get(key);
        if (bp != null)
            return bp;
        Long2ObjectStripedMap<IntBlockPos> old = oldLookup;
        return old != null ? old.get(key) : null;
    }

    public static int size() {
        return lookup.size();
    }

    /** 客户端卸关卡时清空两张表。清掉之后新建的键会在 put 里重新注册，旧世界的键不再有活持有者。 */
    public static void clearAll() {
        lookup.clear();
        Long2ObjectStripedMap<IntBlockPos> old = oldLookup;
        if (old != null) {
            old.clear();
        }
    }

    // --------------------------------------------

    public static void swap() {
        oldLookup = lookup;
        lookup = new Long2ObjectStripedMap<>(1 << 23);
    }
}
