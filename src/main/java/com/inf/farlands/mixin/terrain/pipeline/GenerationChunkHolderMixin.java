package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 地形管线：拦截 vanilla chunk 生成调度，短路到 FULL 空壳。
 *
 * 所有 chunk 生成入口，即 getChunk 任意状态请求与 ChunkHolder 的 prepare 链，都汇聚到
 * scheduleChunkGenerationTask。拦截后不创建 ChunkGenerationTask，生成金字塔一行不跑，改为
 * 读盘或建空壳 ProtoChunk，构造 LevelChunk，逐状态 completeFuture，于是全部请求立即拿到
 * LevelChunk。per-section 生成由独立的 terrain 管线异步驱动。
 *
 * 26.1.2 的 vanilla 方法体内有 task 与 applyStep 机制，本覆写整体替换它且从不设置 task，
 * 因此 ChunkGenerationTask 永不创建，金字塔不会被旁路驱动。
 *
 * 线程：拦截在主线程；scheduleChunkLoad 内部后台读盘加主线程建 ProtoChunk；thenAccept 跟随主线程。
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin {

    // startedWork 必须置 FULL，否则 getLatestChunk 返回 null，saveChunkIfNeeded 永不保存。
    // final 字段用 @Shadow 非 final 声明，mixin 生成 putfield 直写，绕开反射写 final 的限制。
    @Shadow
    private AtomicReference<ChunkStatus> startedWork;

    @Shadow
    public abstract ChunkPos getPos();

    @Shadow
    public abstract FullChunkStatus getFullStatus();

    private static final Method M_SCHEDULE_CHUNK_LOAD;
    private static final Field F_LEVEL;
    private static final Method M_GET_OR_CREATE_FUTURE;
    private static final Method M_COMPLETE_FUTURE;
    private static final Method M_IS_STATUS_DISALLOWED;

    static {
        try {
            M_SCHEDULE_CHUNK_LOAD = ChunkMap.class.getDeclaredMethod("scheduleChunkLoad", ChunkPos.class);
            M_SCHEDULE_CHUNK_LOAD.setAccessible(true);
            F_LEVEL = ChunkMap.class.getDeclaredField("level");
            F_LEVEL.setAccessible(true);
            M_GET_OR_CREATE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("getOrCreateFuture",
                    ChunkStatus.class);
            M_GET_OR_CREATE_FUTURE.setAccessible(true);
            M_COMPLETE_FUTURE = GenerationChunkHolder.class.getDeclaredMethod("completeFuture", ChunkStatus.class,
                    ChunkAccess.class);
            M_COMPLETE_FUTURE.setAccessible(true);
            M_IS_STATUS_DISALLOWED = GenerationChunkHolder.class.getDeclaredMethod("isStatusDisallowed",
                    ChunkStatus.class);
            M_IS_STATUS_DISALLOWED.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private AtomicBoolean farlandsExistenceStarted;

    /**
     * 延迟创建标志，不用字段初始化器。交接文档 §10.1：本类没有显式构造器，
     * 
     * @Unique 实例字段的初始化器可能被静默丢弃，读到时会是 null。
     */
    @Unique
    private AtomicBoolean farlandsExistence() {
        AtomicBoolean b = this.farlandsExistenceStarted;
        if (b == null) {
            b = new AtomicBoolean();
            this.farlandsExistenceStarted = b;
        }
        return b;
    }

    /** 每 holder 短路只跑一次。completeFuture 对已成功完成的 future 再 complete 会抛。 */
    @Overwrite
    public CompletableFuture<ChunkResult<ChunkAccess>> scheduleChunkGenerationTask(ChunkStatus targetStatus,
            ChunkMap chunkMap) {
        if (farlandsIsStatusDisallowed(targetStatus)) {
            return GenerationChunkHolder.UNLOADED_CHUNK_FUTURE;
        }
        CompletableFuture<ChunkResult<ChunkAccess>> future = farlandsGetOrCreateFuture(targetStatus);
        if (future.isDone()) {
            return future;
        }
        if (farlandsExistence().compareAndSet(false, true)) {
            ChunkPos pos = this.getPos();
            try {
                CompletableFuture<ChunkAccess> load = farlandsScheduleChunkLoad(chunkMap, pos);
                load.thenAccept(proto -> this.farlandsCompleteEmpty(chunkMap, proto));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return future;
    }

    /** 主线程：ProtoChunk 转 LevelChunk 容器构造，生命周期，逐状态 completeFuture。 */
    @Unique
    private void farlandsCompleteEmpty(ChunkMap chunkMap, ChunkAccess proto) {
        try {
            ServerLevel level = (ServerLevel) F_LEVEL.get(chunkMap);
            boolean isNew = !(proto instanceof ImposterProtoChunk);
            LevelChunk levelchunk;
            if (!isNew) {
                levelchunk = ((ImposterProtoChunk) proto).getWrapped();
            } else {
                levelchunk = new LevelChunk(level, (ProtoChunk) proto, p -> {
                });
            }
            levelchunk.setFullStatus(this::getFullStatus);
            levelchunk.runPostLoad();
            levelchunk.setLoaded(true);
            levelchunk.registerAllBlockEntitiesAfterLevelLoad();
            levelchunk.registerTickContainerInLevel(level);

            this.startedWork.set(ChunkStatus.FULL);

            for (ChunkStatus status : ChunkStatus.getStatusList()) {
                farlandsCompleteFuture(status, levelchunk);
            }

            // biome 阶段独立：后台按窗口并集填 biome 并升 BIOMES，完成后回主线程做 fsa 读回，
            // 读回完成再入生成队列。读回与入队必须回主线程，thenAccept 在后台线程执行。
            CompletableFuture.runAsync(
                    () -> BiomeFiller.fillChunkBiomes(level, levelchunk),
                    Util.backgroundExecutor())
                    .thenAccept(v -> SectionIO.runOnMainThread(() -> {
                        // fsa 读回：先查磁盘窗口内 section，有则读回恢复数据、光照、stage 并补发。
                        // 完成后才 enqueueChunk，collectSegments 的 isOrAfter(TERRAIN) 自动跳过已读回的，
                        // 磁盘没有的 section 正常入生成队列。
                        SectionLifecycle.loadChunkSections(levelchunk,
                                () -> GenQueue.enqueueChunk(levelchunk));
                    }, level));
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("farlands: existence flow failed chunk={}", proto.getPos(), e);
            throw new RuntimeException(e);
        }
    }

    @Unique
    private boolean farlandsIsStatusDisallowed(ChunkStatus status) {
        try {
            return (Boolean) M_IS_STATUS_DISALLOWED.invoke(this, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Unique
    private CompletableFuture<ChunkResult<ChunkAccess>> farlandsGetOrCreateFuture(ChunkStatus status) {
        try {
            return (CompletableFuture<ChunkResult<ChunkAccess>>) M_GET_OR_CREATE_FUTURE.invoke(this, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private void farlandsCompleteFuture(ChunkStatus status, ChunkAccess chunk) {
        try {
            M_COMPLETE_FUTURE.invoke(this, status, chunk);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Unique
    private static CompletableFuture<ChunkAccess> farlandsScheduleChunkLoad(ChunkMap chunkMap, ChunkPos pos) {
        try {
            return (CompletableFuture<ChunkAccess>) M_SCHEDULE_CHUNK_LOAD.invoke(chunkMap, pos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
