package com.inf.farlands.serialize;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * chunk 的数据就绪判据与 FULL 的补发点。
 *
 * <p>「就绪」指该 chunk 此刻不该再等任何数据：生成、光照、群系三条在途链都不在跑，没有读回在途，
 * 不在等窗口建立，且已物化段里没有做过但没点亮的段。四条判据的原料分散在 GenQueue、SectionIO、
 * SectionLifecycle 与 SectionStage 四处，本类把它们收成一个查询。
 *
 * <p>FULL 的补发从模板的完成循环里拿了出来：存在流程只置到 SPAWN，FULL 由这里的 drive 在就绪时补。
 * 低一档的 SPAWN 持有 LevelChunk，getLatestChunk 的回退因此仍返回它，存盘与 fsa 遍历不受影响。
 *
 * <p>线程：登记在主线程的存在流程里，drive 在主线程每 tick 末尾，预加载循环也在主线程。readingInFlight
 * 的标记同样只在主线程改写，所以这里不做额外同步。
 */
public final class ChunkReadiness {

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    /** 一个已过存在流程的 chunk：holder 用来补 FULL，chunk 用来求就绪。 */
    private record Watched(LevelChunk chunk, GenerationChunkHolder holder) {
    }

    private static final Map<Key, Watched> WATCHED = new ConcurrentHashMap<>();

    /** 补 FULL 的入口，反射一次。与 GenerationChunkHolderMixin 里那份指向同一个私有方法。 */
    private static final Method M_COMPLETE_FUTURE;

    static {
        try {
            M_COMPLETE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("completeFuture",
                    ChunkStatus.class, ChunkAccess.class);
            M_COMPLETE_FUTURE.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("farlands: reflection on GenerationChunkHolder.completeFuture failed", e);
        }
    }

    /** 关服标志：置位后存盘门改看 isChunkBusy，让窗口永不建立时也写得下 chunk NBT。 */
    private static volatile boolean shuttingDown;

    private ChunkReadiness() {
    }

    /** 存在流程建好 LevelChunk 后登记。主线程。 */
    public static void watch(GenerationChunkHolder holder, LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        WATCHED.put(new Key(level.dimension(), chunk.getPos().pack()), new Watched(chunk, holder));
    }

    /** 该 chunk 的数据是否已就绪。主线程调用。 */
    public static boolean isDataReady(LevelChunk chunk) {
        if (TerrainHooks.isChunkBusy(chunk)) {
            return false;
        }
        if (GenQueue.isBiomeFilling(chunk)) {
            return false;
        }
        if (SectionIO.isReadingAny(chunk.getPos().pack())) {
            return false;
        }
        if (SectionLifecycle.isPendingWindowRead(chunk)) {
            return false;
        }
        return !SectionStage.hasBelowLighted(chunk);
    }

    /** 按位置取当前的 LevelChunk，未建壳返回 null。非阻塞，供写入门与预加载用。主线程调。 */
    public static LevelChunk chunkAt(ServerLevel level, ChunkPos pos) {
        return SectionLifecycle.latestChunk(level, pos.x(), pos.z());
    }

    /** 该位置是否已就绪。没建壳即未就绪。 */
    public static boolean isReady(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = chunkAt(level, pos);
        return chunk != null && isDataReady(chunk);
    }

    /**
     * 把已就绪的 chunk 的 FULL 补上，并从登记表移除。
     *
     * <p>每 tick 末尾由 FarlandsTick 调一次；预加载循环里也显式调，因为 prepareLevels 期间没有 tick。
     * 只补一次：补完即从表里移除，completeFuture 对已成功的 future 再补会抛。
     */
    public static void drive() {
        if (WATCHED.isEmpty()) {
            return;
        }
        boolean windowOpen = EntitySectionWindow.ranges().length > 0;
        for (Map.Entry<Key, Watched> entry : WATCHED.entrySet()) {
            Watched watched = entry.getValue();
            LevelChunk chunk = watched.chunk();
            if (windowOpen && SectionLifecycle.isPendingWindowRead(chunk)) {
                SectionLifecycle.loadChunkSections(chunk, () -> {
                });
            }
            if (!isDataReady(chunk) || !WATCHED.remove(entry.getKey(), watched)) {
                continue;
            }
            try {
                M_COMPLETE_FUTURE.invoke(watched.holder(), ChunkStatus.FULL, chunk);
            } catch (Exception e) {
                throw new RuntimeException("farlands: failed to complete FULL for " + chunk.getPos(), e);
            }
        }
    }

    /** chunk 卸载时丢弃登记项。 */
    public static void discard(ServerLevel level, ChunkPos pos) {
        WATCHED.remove(new Key(level.dimension(), pos.pack()));
    }

    /** 关服入口置位，由 MinecraftServerMixin 在 stopServer 的 HEAD 调。 */
    public static void markShuttingDown() {
        shuttingDown = true;
    }

    public static boolean isShuttingDown() {
        return shuttingDown;
    }
}
