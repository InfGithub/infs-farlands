package com.inf.farlands.util.maps;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.InfSFarlands;
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
        InfSFarlands.LOGGER.warn("Hash Conflicted! {} key=0x{} old={},{},{} new={},{},{}",
                kind, Long.toHexString(key), ox, oy, oz, nx, ny, nz);
    }

    private record PendingKey(ResourceKey<Level> dimension, long chunkPos) {
    }

    @SuppressWarnings("unused")
    private static final class PendingSections {
        final int minY;
        final List<ChunkDataPacket.SectionEntry> entries = new ArrayList<>();

        PendingSections(int minY) {
            this.minY = minY;
        }
    }

    private static final ConcurrentHashMap<PendingKey, PendingSections> PENDING_SECTION_DATA = new ConcurrentHashMap<>();

    public static void discardPendingSectionData(ResourceKey<Level> dimension, ChunkPos pos) {
        PENDING_SECTION_DATA.remove(new PendingKey(dimension, pos.pack()));
    }
}
