package com.inf.farlands.serialize;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.pipeline.GenQueue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * chunk 的数据就绪判据，promotion 门的唯一入口。
 *
 * <p>「就绪」指该 chunk 此刻不该再等任何数据：生成、光照、群系三条在途链都不在跑，没有读回在途，
 * 不在等窗口建立，且已物化段里没有做过但没点亮的段。四条判据的原料分散在 GenQueue、SectionIO、
 * SectionLifecycle 与 SectionStage 四处，本类把它们收成一个查询。
 *
 * <p>线程：全部调用点都在主线程。readingInFlight 的标记也只在主线程改写，所以这里不做额外同步。
 * 判据里的布尔载体本身是 CHM、AtomicBoolean 或 volatile。
 *
 * <p>就绪成立后不会因为窗口移动翻回假：清理删段会连同 stage 条目一起删，而未处理段不属于「低于
 * LIGHTED」。窗口外实体的冻结由实体刻的位置门单独负责，不由本判据承担。
 */
public final class ChunkReadiness {

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    private record Waiter(ServerLevel level, ChunkPos pos, CompletableFuture<Void> future) {
    }

    /** 挂起的就绪等待。key 含维度，跨维度同坐标不互相牵连。 */
    private static final Map<Key, Waiter> WAITING = new ConcurrentHashMap<>();

    private ChunkReadiness() {
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

    /**
     * 该 chunk 数据就绪时完成的 future，已经就绪则返回已完成的 future。
     *
     * <p>调用点拿不到 chunk 本身也能用：promotion 的调用点只持有 holder，此时存在流程可能还没跑，
     * getLatestChunk 仍是 null。真正的判定留给 drive，它每 tick 从 level 反查当前 chunk。
     */
    public static CompletableFuture<Void> whenReady(ServerLevel level, ChunkPos pos) {
        LevelChunk now = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (now != null && isDataReady(now)) {
            return CompletableFuture.completedFuture(null);
        }
        Key key = new Key(level.dimension(), pos.pack());
        return WAITING.computeIfAbsent(key, k -> new Waiter(level, pos, new CompletableFuture<>())).future();
    }

    /**
     * 驱动挂起的等待。只在主线程每 tick 调一次，放在窗口并集刷新与各条在途链的当 tick 推进之后，
     * 让当 tick 变成就绪的 chunk 在同一 tick 放行。异步完成的读回与生成要等下一 tick，属可接受延迟。
     */
    public static void drive() {
        if (WAITING.isEmpty()) {
            return;
        }
        for (Map.Entry<Key, Waiter> entry : WAITING.entrySet()) {
            Waiter waiter = entry.getValue();
            LevelChunk chunk = waiter.level().getChunkSource().getChunkNow(waiter.pos().x(), waiter.pos().z());
            if (chunk != null && isDataReady(chunk) && WAITING.remove(entry.getKey(), waiter)) {
                waiter.future().complete(null);
            }
        }
    }

    /**
     * chunk 卸载时丢弃挂起项。这里不能 complete：正在卸载的 chunk 不该被放行到 promotion 的后续动作，
     * 那个 promotion future 本身由 ChunkHolder 的降级路径以 UNLOADED 结清。
     */
    public static void discard(ServerLevel level, ChunkPos pos) {
        WAITING.remove(new Key(level.dimension(), pos.pack()));
    }
}
