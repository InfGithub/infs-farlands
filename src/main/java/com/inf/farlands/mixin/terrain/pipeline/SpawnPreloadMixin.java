package com.inf.farlands.mixin.terrain.pipeline;

import java.util.HashSet;
import java.util.Set;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.pipeline.SpawnPreload;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelData;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 出生区预加载：主循环开始之前，把出生点附近的段推到 LIGHTED。
 *
 * <p>
 * 它是入场前的硬前提。PlayerSpawnFinder 在玩家进名单之前就用两参 getChunk 取整块并读高度图，
 * 那条路是阻塞 FULL，而此刻没有玩家、没有窗口、就绪按设计不可达：只有这里先把出生区做成就绪，那次读
 * 才不会永久停在 managedBlock 上。所以循环没有墙钟上限，退出条件只剩「范围内全部点亮」；不收敛时只报
 * 一次停滞明细，不早退，因为早退的结局同样是把玩家关在加载界面外，只是挂得更晚、更没有诊断。
 *
 * <p>
 * 预加载期间窗口并集为空，而生成与读回都按窗口驱动，所以这里走显式段范围：GenQueue.preload 绕过
 * tracking view 过滤，SectionLifecycle.preloadRange 用同一范围发起读回，并清掉窗口等待标记，否则
 * ChunkReadiness.isDataReady 里那一条恒假，FULL 补不出来，入场后连方块都放不下去。
 *
 * <p>
 * 范围内每段先种 biome 再入队：窗口并集为空时 BiomeFiller.fillChunkBiomes 什么都不会种，而
 * fillSectionBiomes 有 stage 小于 BIOMES 的门，等生成把 stage 推上去之后再补就跳过了，出生区会按
 * 默认群系成型。
 *
 * <p>
 * 驱动：prepareLevels 在主循环之前，ticket 到 chunk 的调度要手动 cache.tick，短路链的
 * thenApplyAsync 要 waitUntilNextTick 的 runAllTasks。每轮让出一次 tick，防忙循环饿死同 JVM 的渲染。
 *
 * <p>
 * 票留到该维度第一个玩家入场才由 SpawnPreloadJoinMixin 移除。
 */
@Mixin(MinecraftServer.class)
public abstract class SpawnPreloadMixin {

    /** 出生点搜索的块级偏移最多跨一个 chunk，PLAYER_SPAWN 的半径是 3，取 4 覆盖两者。 */
    @Unique
    private static final int FARLANDS_PRELOAD_RADIUS = 4;

    /** 竖直半高，与上一版本一致。 */
    @Unique
    private static final int FARLANDS_PRELOAD_HALF_Y = 3;

    /**
     * 连续多少毫秒 ready 不增才算停滞。取 30 秒是刻意的宽：每轮固定让出 10 毫秒，而世界生成的一个
     * 批次间隔本来就可能上秒，按轮数计会把正常批次判成停滞，刷出一堆假警报。
     */
    @Unique
    private static final long FARLANDS_PRELOAD_STALL_MILLIS = 30_000L;

    /** 停滞明细最多列几个 chunk。 */
    @Unique
    private static final int FARLANDS_PRELOAD_STALL_LIST = 8;

    @Shadow
    private long nextTickTimeNanos;

    @Shadow
    @Final
    private static long PREPARE_LEVELS_DEFAULT_DELAY_NANOS;

    @Shadow
    protected abstract void waitUntilNextTick();

    @SuppressWarnings("resource")
    @Inject(method = "prepareLevels", at = @At("TAIL"))
    private void farlands$preloadSpawnArea(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 中心与 PlayerSpawnFinder 的建议点同源：它取的就是存量 respawn data 的 pos，
        // 维度取该 data 的维度，缺失时退回主世界。
        LevelData.RespawnData respawnData = server.getWorldData().overworldData().getRespawnData();
        ServerLevel level = server.getLevel(respawnData.dimension());
        if (level == null) {
            level = server.overworld();
        }
        BlockPos spawnPos = respawnData.pos();
        int spawnCx = SectionPos.blockToSectionCoord(spawnPos.getX());
        int spawnCz = SectionPos.blockToSectionCoord(spawnPos.getZ());
        int spawnSy = SectionPos.blockToSectionCoord(spawnPos.getY());
        int minSy = Math.max(spawnSy - FARLANDS_PRELOAD_HALF_Y, WorldBounds.MIN_SECTION);
        int maxSy = Math.min(spawnSy + FARLANDS_PRELOAD_HALF_Y, WorldBounds.MAX_SECTION);
        int syCount = 0;
        for (int sy = minSy; sy <= maxSy; sy++) {
            if (WorldBounds.inSection(sy)) {
                syCount++;
            }
        }
        int side = FARLANDS_PRELOAD_RADIUS * 2 + 1;
        int total = syCount * side * side;
        if (total <= 0) {
            return;
        }

        ServerChunkCache cache = level.getChunkSource();
        Set<Long> issued = new HashSet<>();
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(spawnCx + dx, spawnCz + dz);
                cache.addTicketWithRadius(GenQueue.GEN_WORK_TICKET, pos, 0);
                SpawnPreload.register(level.dimension(), pos);
            }
        }

        int ready = farlands$countReady(level, spawnCx, spawnCz, minSy, maxSy);
        long startedAt = System.currentTimeMillis();
        long lastProgressAt = startedAt;
        int lastReady = ready;
        while (ready < total) {
            cache.tick(() -> false, false);
            this.waitUntilNextTick();
            // 每轮补一次 FULL：出生区里已就绪的 chunk 越早拿到 FULL，入场前那次阻塞读越早不会撞上。
            ChunkReadiness.drive();
            for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS; dx++) {
                for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS; dz++) {
                    ChunkPos pos = new ChunkPos(spawnCx + dx, spawnCz + dz);
                    LevelChunk chunk = ChunkReadiness.chunkAt(level, pos);
                    if (chunk == null) {
                        continue;
                    }
                    SectionLifecycle.clearPendingWindowRead(chunk);
                    if (issued.add(pos.pack())) {
                        SectionLifecycle.preloadRange(chunk, minSy, maxSy);
                        for (int sy = minSy; sy <= maxSy; sy++) {
                            if (WorldBounds.inSection(sy)) {
                                BiomeFiller.fillSectionBiomes(level, chunk, sy);
                            }
                        }
                    }
                    if (!SectionIO.isReadingAny(pos.pack())) {
                        GenQueue.preload(chunk, minSy, maxSy);
                    }
                }
            }
            ready = farlands$countReady(level, spawnCx, spawnCz, minSy, maxSy);
            long now = System.currentTimeMillis();
            if (ready != lastReady) {
                lastReady = ready;
                lastProgressAt = now;
            } else if (now - lastProgressAt >= FARLANDS_PRELOAD_STALL_MILLIS) {
                // 每个停滞窗口报一次：报完把基准推到现在，持续停滞才会每 30 秒再报一条。
                InfsFarlands.LOGGER.error("farlands: spawn preload stuck at {}/{} for {} ms, detail: {}",
                        ready, total, now - lastProgressAt,
                        farlands$stallDetail(level, spawnCx, spawnCz, minSy, maxSy, syCount));
                lastProgressAt = now;
            }
            this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
            this.waitUntilNextTick();
        }

        // 存在流程派发的 loadChunkSections 可能晚于范围读回，落完队列再清一次窗口等待标记，
        // 然后才放行 FULL。
        cache.tick(() -> false, false);
        this.waitUntilNextTick();
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS; dz++) {
                LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(spawnCx + dx, spawnCz + dz));
                if (chunk != null) {
                    SectionLifecycle.clearPendingWindowRead(chunk);
                }
            }
        }
        ChunkReadiness.drive();
        InfsFarlands.LOGGER.info("farlands: spawn preload {}/{} in {} ms", ready, total,
                System.currentTimeMillis() - startedAt);
    }

    /** 范围内已点亮的段数。 */
    @Unique
    private int farlands$countReady(ServerLevel level, int centerCx, int centerCz, int minSy, int maxSy) {
        int ready = 0;
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS; dz++) {
                LevelChunk chunk = ChunkReadiness.chunkAt(level, new ChunkPos(centerCx + dx, centerCz + dz));
                if (chunk == null) {
                    continue;
                }
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (WorldBounds.inSection(sy) && SectionStage.isOrAfter(chunk, sy, SectionStage.LIGHTED)) {
                        ready++;
                    }
                }
            }
        }
        return ready;
    }

    /** 停滞明细：还没点亮的 chunk，最多列若干个，带在途标志与未点亮段数。 */
    @Unique
    private String farlands$stallDetail(ServerLevel level, int centerCx, int centerCz, int minSy, int maxSy,
            int perChunk) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (int dx = -FARLANDS_PRELOAD_RADIUS; dx <= FARLANDS_PRELOAD_RADIUS
                && listed < FARLANDS_PRELOAD_STALL_LIST; dx++) {
            for (int dz = -FARLANDS_PRELOAD_RADIUS; dz <= FARLANDS_PRELOAD_RADIUS
                    && listed < FARLANDS_PRELOAD_STALL_LIST; dz++) {
                ChunkPos pos = new ChunkPos(centerCx + dx, centerCz + dz);
                LevelChunk chunk = ChunkReadiness.chunkAt(level, pos);
                if (chunk == null) {
                    sb.append(pos).append("[no-shell] ");
                    listed++;
                    continue;
                }
                int done = 0;
                for (int sy = minSy; sy <= maxSy; sy++) {
                    if (WorldBounds.inSection(sy) && SectionStage.isOrAfter(chunk, sy, SectionStage.LIGHTED)) {
                        done++;
                    }
                }
                if (done >= perChunk) {
                    continue;
                }
                int[] below = { 0 };
                SectionStage.forEachStage(chunk, (sy, stage) -> {
                    if (SectionStage.isBelowLighted(stage)) {
                        below[0]++;
                    }
                });
                sb.append(pos).append("[lighted=").append(done).append('/').append(perChunk)
                        .append(",busy=").append(GenQueue.isChunkBusy(chunk))
                        .append(",biome=").append(GenQueue.isBiomeFilling(chunk))
                        .append(",reading=").append(SectionIO.isReadingAny(pos.pack()))
                        .append(",pending=").append(SectionLifecycle.isPendingWindowRead(chunk))
                        .append(",belowLighted=").append(below[0]).append("] ");
                listed++;
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
