package com.inf.farlands.terrain.decorationFiller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 装饰阶段编排：待装饰表、邻域门、写域认领、提交到 farlands-gen、主线程收尾。
 *
 * <p>装饰是 XZ 网格驱动的 3D 写入，不能按 section 原子化：写入落在目标 chunk 与 XZ 八个邻居上，
 * 而那一圈在写入之前必须都已经过地形与雕刻。门因此取「九宫格每一格都有过段，且已有段都到
 * CARVERS」，与 vanilla ChunkPyramid 的 addRequirement(CARVERS, 1) 同源。
 *
 * <p>线程：门、认领、提交与收尾都在主线程，装饰体在 farlands-gen。取数一律走
 * {@link SectionLifecycle#latestChunk}，它只在主线程可调，所以九格句柄由主线程取好交给任务。
 *
 * <p>失败一律不抛：门没过就留表等下一轮，与 surface、carve 两族同形。
 *
 * <p>地物在这一遍里跑：池上建区域、调 ChunkGenerator.applyBiomeDecoration，写直进真实段；收尾回
 * 主线程装方块实体、升段、标脏、补发、触发光照。结构的两相不在这里，见方案第 6.2 节。
 */
public final class DecorationFiller {

    /** 门与写域的半径，取 vanilla 的 blockStateWriteRadius(1)。 */
    private static final int NEIGHBORHOOD_RADIUS = 1;

    /** 待装饰的 chunk：门没过的、以及已提交等收尾的。键是维度加 chunk 坐标。 */
    private static final Map<Key, ServerLevel> PENDING = new ConcurrentHashMap<>();

    /**
     * 放弃原因只打前若干条。门没过的项每轮都会重试，不限流会把日志刷爆；形状与 SectionLifecycle 的
     * ENCODE_FAIL_LOGGED 相同。
     */
    private static final AtomicInteger ABORT_LOGGED = new AtomicInteger();

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    private DecorationFiller() {
    }

    /** 该 chunk 是否还有已过雕刻、尚未装饰的段。CARVERS 是唯一待装饰的取值。 */
    public static boolean hasDecorationPending(LevelChunk chunk) {
        boolean[] found = { false };
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage == SectionStage.CARVERS) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 登记待装饰。雕刻完成后由 GenTask 调，扫描路径也调它兜底。
     *
     * <p>登记不去重：已提交的项由 {@link DecorationClaim} 挡住重复提交，门没过的项本来就要留表。
     */
    public static void register(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!hasDecorationPending(chunk)) {
            return;
        }
        PENDING.put(new Key(level.dimension(), chunk.getPos().pack()), level);
    }

    /**
     * 每 tick 一次的主线程驱动：逐项判门、取句柄、认领写域与光照域、提交到 farlands-gen。
     *
     * <p>提交成功的项留表直到收尾删掉；这期间认领把同一个中心挡在门外，所以不会重复提交。
     */
    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Key key : new ArrayList<>(PENDING.keySet())) {
            ServerLevel level = PENDING.get(key);
            if (level == null) {
                continue;
            }
            LevelChunk center = SectionLifecycle.latestChunk(level, ChunkPos.getX(key.chunkPos()),
                    ChunkPos.getZ(key.chunkPos()));
            if (center == null) {
                PENDING.remove(key);
                continue;
            }
            if (!hasDecorationPending(center)) {
                PENDING.remove(key);
                continue;
            }
            // 中心有引用（有结构的包围盒与它相交）时，结构放置会读到 ±8；没有引用就没有结构放置，
            // 读不出写域之外，维持 ±2 省钱。
            int readRadius = center.getAllReferences().isEmpty() ? DecorationRegion.READ_RADIUS
                    : StructureDriver.STRUCTURE_READ_RADIUS;
            Map<Long, LevelChunk> handles = neighborhood(level, center, readRadius);
            if (handles == null) {
                continue; // 门没过：留表等下一轮
            }
            int cx = center.getPos().x();
            int cz = center.getPos().z();
            if (!DecorationClaim.tryClaim(level.dimension(), cx, cz)) {
                continue; // 写域与另一次装饰相交，留表等下一轮
            }
            FarLandsLightEngine lightEngine = lightEngineOf(level);
            if (lightEngine != null && !lightEngine.tryLockDomain(cx, cz)) {
                // 光照任务正在这一片跑：它的读与我们的写入要互斥，撤认领、留表等下一轮。
                DecorationClaim.release(level.dimension(), cx, cz);
                continue;
            }
            if (!GenQueue.submitDecoration(new DecorationTask(level, center, key, handles, readRadius))) {
                // 池已关，即停服：撤两把锁、留表等下一轮
                DecorationClaim.release(level.dimension(), cx, cz);
                if (lightEngine != null) {
                    lightEngine.unlockDomain(cx, cz);
                }
                return;
            }
        }
    }

    /** 该 level 的光照引擎。非本 port 引擎返回 null，此时装饰侧不取光照域锁。 */
    private static FarLandsLightEngine lightEngineOf(ServerLevel level) {
        return level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine engine ? engine : null;
    }

    /**
     * 写域九格的句柄与门，另加读域外环的句柄。返回 null 表示门没过：写域某格缺席、某格一个段都没有、
     * 或某格还有未过雕刻的段。
     *
     * <p>句柄在这里取好交给任务：chunk 的窗口容器与服务端的 chunk 表都不是线程安全的，池线程不得
     * 再查，所以取数是主线程的事。写域（±1）必须齐；读域外环（到 {@link DecorationRegion#READ_RADIUS}）
     * 有就带上、没有就跳过——地物偶尔会读到写域外一格，缺句柄时那一次读会放弃并记日志，但门不因此
     * 拦住整遍装饰。
     *
     * <p>「已有段都到 CARVERS」是写者判据：到 CARVERS 的邻居不会再被 genPool 写它自己的段，
     * 因为 collectSegments 只收未 TERRAIN 的段，而 surface 与 carvers 的 pending 判据都不认
     * CARVERS 段。「有过段」这一条不能省：一个段都没有的 chunk 既可能还没开始生成，也可能地形
     * 已产出但没建段，只有后者能承载装饰。
     */
    private static Map<Long, LevelChunk> neighborhood(ServerLevel level, LevelChunk center, int readRadius) {
        Map<Long, LevelChunk> handles = new HashMap<>();
        ChunkPos pos = center.getPos();
        for (int dx = -NEIGHBORHOOD_RADIUS; dx <= NEIGHBORHOOD_RADIUS; dx++) {
            for (int dz = -NEIGHBORHOOD_RADIUS; dz <= NEIGHBORHOOD_RADIUS; dz++) {
                LevelChunk neighbor = SectionLifecycle.latestChunk(level, pos.x() + dx, pos.z() + dz);
                if (neighbor == null) {
                    return null;
                }
                long neighborKey = neighbor.getPos().pack();
                boolean[] any = { false };
                boolean[] belowCarvers = { false };
                SectionStage.forEachStage(neighbor, (sy, stage) -> {
                    any[0] = true;
                    if (stage < SectionStage.CARVERS && !SectionIO.isReading(neighborKey, sy)) {
                        belowCarvers[0] = true;
                    }
                });
                if (!any[0] || belowCarvers[0]) {
                    return null;
                }
                handles.put(neighborKey, neighbor);
            }
        }
        for (int dx = -readRadius; dx <= readRadius; dx++) {
            for (int dz = -readRadius; dz <= readRadius; dz++) {
                if (Math.abs(dx) <= NEIGHBORHOOD_RADIUS && Math.abs(dz) <= NEIGHBORHOOD_RADIUS) {
                    continue; // 写域那九格已经收过
                }
                LevelChunk neighbor = SectionLifecycle.latestChunk(level, pos.x() + dx, pos.z() + dz);
                if (neighbor != null) {
                    handles.put(neighbor.getPos().pack(), neighbor);
                }
            }
        }
        return handles;
    }

    /**
     * 一个装饰任务。跑在 farlands-gen 上：建区域、跑 applyBiomeDecoration，然后无论成败都回主线程
     * 收尾。
     */
    private static final class DecorationTask implements Runnable {

        private final ServerLevel level;
        private final LevelChunk center;
        private final Key key;
        private final Map<Long, LevelChunk> handles;
        /** 本任务的读半径：由主线程按「中心有无引用」定好交下来，池上不再读那张表。 */
        private final int readRadius;

        DecorationTask(ServerLevel level, LevelChunk center, Key key, Map<Long, LevelChunk> handles,
                int readRadius) {
            this.level = level;
            this.center = center;
            this.key = key;
            this.handles = handles;
            this.readRadius = readRadius;
        }

        @Override
        public void run() {
            DecorationRegion region = null;
            try {
                DecorationContext.enter();
                try {
                    CarverFiller.primeFinalHeightmaps(this.center);
                    region = new DecorationRegion(this.level, this.center, this.handles, this.readRadius);
                    // 结构起点的 Y 锚会经 getFirstOccupiedHeight 构造 NoiseChunk，而 NoiseChunk 的
                    // 构造点要求 TerrainSystemContext 已设，否则抛。括号照 fill 的形状。
                    TerrainSystemContext.set(((LevelSystems) this.level).terrainSystem());
                    try {
                        this.level.getChunkSource().getGenerator().applyBiomeDecoration(region, this.center,
                                ScopedStructureManager.of(this.level, region));
                    } finally {
                        TerrainSystemContext.clear();
                    }
                } finally {
                    DecorationContext.exit();
                }
            } catch (DecorationAbort abort) {
                // 条件不成立：放弃本次，丢弃已建的方块实体，留表下一轮。已直写进段的方块不回滚，
                // 下一轮的落点以当时内容为准，与「放弃并重试」的语义一致。
                if (ABORT_LOGGED.getAndIncrement() < 20) {
                    InfsFarlands.LOGGER.info("farlands: decoration aborted chunk={},{} reason={}",
                            this.center.getPos().x(), this.center.getPos().z(), abort.getMessage());
                }
                region = null;
            } catch (RuntimeException e) {
                InfsFarlands.LOGGER.error("farlands: decoration failed chunk={},{}",
                        this.center.getPos().x(), this.center.getPos().z(), e);
                region = null;
            } catch (Throwable t) {
                // 池任务走 POOL.submit，没人读那个 Future，任何 Error 都会被静默吞掉。这里显式打出来，
                // 并标明它死在取数之前还是之后：region 未赋值时收尾不会升段，症状正是「装饰永远不完成」。
                InfsFarlands.LOGGER.error("farlands: decoration threw chunk={},{} regionAssigned={}",
                        this.center.getPos().x(), this.center.getPos().z(), region != null, t);
                region = null;
            } finally {
                // 收尾在主线程：装方块实体、升段、标脏、补发、触发光照、撤两把锁与在途计数。
                DecorationRegion computed = region;
                SectionIO.runOnMainThread(() -> finish(this.level, this.center, this.key, computed), this.level);
            }
        }
    }

    /**
     * 收尾。主线程执行：装方块实体、把该 chunk 已过雕刻的段升 DECORATED、标脏、补发、触发光照，
     * 并撤两把锁与在途计数。
     *
     * <p>升段只认 CARVERS，即该 chunk 自己的那一段：邻居被写到的段不跟着升，否则「被写到」会被
     * 当成「它自己的装饰完成」，而那正是本档要消灭的那个混淆。
     *
     * <p>{@code region} 为 null 表示本次放弃，取数不成立或抛异常，此时只撤锁、不升段，留表重试。
     */
    private static void finish(ServerLevel level, LevelChunk center, Key key, DecorationRegion region) {
        try {
            PENDING.remove(key);
            if (region == null) {
                return;
            }
            LevelChunk current = SectionLifecycle.latestChunk(level, center.getPos().x(), center.getPos().z());
            if (current == null) {
                return;
            }
            // 方块实体按各自所属的 chunk 安装：写域是九格，实体可能落在邻居上。
            for (Map.Entry<BlockPos, BlockEntity> e : region.pendingBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                LevelChunk owner = SectionLifecycle.latestChunk(level, SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()));
                if (owner == null) {
                    continue;
                }
                BlockEntity blockEntity = e.getValue();
                blockEntity.setLevel(level);
                owner.setBlockEntity(blockEntity);
            }
            List<Integer> carvers = new ArrayList<>();
            SectionStage.forEachStage(current, (sy, stage) -> {
                if (stage == SectionStage.CARVERS) {
                    carvers.add(sy);
                }
            });
            if (!carvers.isEmpty()) {
                for (int sy : carvers) {
                    SectionStage.setStage(current, sy, SectionStage.DECORATED);
                    ((WindowedChunk) current).markSectionDirty(sy);
                }
                // 段内容此刻才算定下来：DECORATED 到 LIGHTED 只能由光照回调推进，所以这里必须触发
                // 一次光照。
                GenQueue.notifyGenerated(current);
            }
            // 下发标记：写域是九格，邻居的方块也被写过，它们各自的下发要跟着这次装饰走。
            for (long writtenKey : region.writtenChunks()) {
                LevelChunk owner = SectionLifecycle.latestChunk(level, ChunkPos.getX(writtenKey),
                        ChunkPos.getZ(writtenKey));
                if (owner != null) {
                    ChunkDataSender.markChunkChanged(owner);
                }
            }
        } finally {
            DecorationClaim.release(level.dimension(), center.getPos().x(), center.getPos().z());
            FarLandsLightEngine lightEngine = lightEngineOf(level);
            if (lightEngine != null) {
                lightEngine.unlockDomain(center.getPos().x(), center.getPos().z());
            }
            GenQueue.finishDecoration();
        }
    }

    /** 停服时清空待装饰表。认领表由 {@link DecorationClaim#clearWorldState()} 清。 */
    public static void clearWorldState() {
        PENDING.clear();
    }
}
