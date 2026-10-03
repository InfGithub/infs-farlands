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
 * 而那一圈在写入之前必须都已经过地形与雕刻。门因此取九宫格每一格都有过段、且已有段都到 CARVERS，
 * 与 vanilla ChunkPyramid 的 addRequirement(CARVERS, 1) 同源。
 *
 * <p>线程：门、认领、提交与收尾都在主线程，装饰体在 farlands-gen。取数一律走
 * {@link SectionLifecycle#latestChunk}，它只在主线程可调，所以九格句柄由主线程取好交给任务。
 *
 * <p>失败一律不抛：门没过就留表等下一轮，与 surface、carve 两族同形。
 *
 * <p>地物在这一遍里跑：池上建区域、调 ChunkGenerator.applyBiomeDecoration，写直进真实段；收尾回
 * 主线程装方块实体、升段、标脏、补发、触发光照。结构的两相不在这里：它必须早于 fill，落在存在流程里。
 */
public final class DecorationFiller {

    /** 门与写域的半径，取 vanilla 的 blockStateWriteRadius(1)。 */
    private static final int NEIGHBORHOOD_RADIUS = 1;

    /** 待装饰的 chunk：门没过的、以及已提交等收尾的。键是维度加 chunk 坐标。 */
    private static final Map<Key, ServerLevel> PENDING = new ConcurrentHashMap<>();

    /**
     * 每个待装饰项在登记时记下的、处于 CARVERS 的段。待装饰判定因此只查这几段，不再扫全段。
     *
     * <p>登记来自两条路：GenTask 手里已有本次雕刻返回的段数组，直接给；扫描兜底那条给 null，就地
     * 扫一次。同一项重复登记取并集，窗口滑入后新到 CARVERS 的段要能并进来，覆盖会丢段。
     */
    private static final Map<Key, java.util.Set<Integer>> PENDING_SECTIONS = new ConcurrentHashMap<>();

    /**
     * 放弃原因只打前若干条。门没过的项每轮都会重试，不限流会把日志刷爆；形状与 SectionLifecycle 的
     * ENCODE_FAIL_LOGGED 相同。
     */
    private static final AtomicInteger ABORT_LOGGED = new AtomicInteger();

    /**
     * 格状态变化的全局纪元：任何一格出现、消失、档位推进或读回落段都加一。
     *
     * <p>门只取决于写域九格的状态，所以纪元没变就说明九格的输入没变过，上次的结论仍然成立，整门可省。
     * 每项记下自己上次评估门时的纪元，见 {@link #LAST_EVAL_EPOCH}。这条把每 tick 每项 290 格的扫描
     * 压成只在有格变化时扫；信号漏发只会让某一项晚一点再试，不会让它永久停摆，下一次任何格变化都会
     * 把它带进来。
     */
    private static final java.util.concurrent.atomic.AtomicLong cellEpoch =
            new java.util.concurrent.atomic.AtomicLong(1);

    /** 每项上次评估门时的纪元。纪元相同则跳过整门。 */
    private static final Map<Key, Long> LAST_EVAL_EPOCH = new ConcurrentHashMap<>();

    /** 某一格的状态变了。主线程调用，触发点是存在流程建壳、GenTask 推到 CARVERS、读回落段、收尾升段、卸载。 */
    public static void cellChanged() {
        cellEpoch.incrementAndGet();
    }

    private record Key(ResourceKey<Level> dimension, long chunkPos) {
    }

    private DecorationFiller() {
    }

    /**
     * 该 chunk 是否还有已过雕刻、尚未装饰的段。CARVERS 是唯一待装饰的取值。
     *
     * <p>有登记集合时只查集合里那几段，即登记时记下的 CARVERS 段；没有登记时退回全段扫描，覆盖
     * 尚未登记与兜底路径就地扫过两种情形。
     */
    public static boolean hasDecorationPending(LevelChunk chunk) {
        return hasDecorationPending(chunk, null);
    }

    private static boolean hasDecorationPending(LevelChunk chunk, java.util.Set<Integer> recorded) {
        java.util.Set<Integer> sections = recorded;
        if (sections == null) {
            sections = PENDING_SECTIONS.get(keyOf(chunk));
        }
        if (sections != null) {
            for (int sy : sections) {
                if (SectionStage.getStage(chunk, sy) == SectionStage.CARVERS) {
                    return true;
                }
            }
            return false;
        }
        boolean[] found = { false };
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage == SectionStage.CARVERS) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static Key keyOf(LevelChunk chunk) {
        return new Key(((ServerLevel) chunk.getLevel()).dimension(), chunk.getPos().pack());
    }

    /**
     * 登记待装饰。雕刻完成后由 GenTask 调，带上本次雕刻的段；扫描路径调它兜底，传 null。
     *
     * <p>登记不去重：已提交的项由 {@link DecorationClaim} 挡住重复提交，门没过的项本来就要留表。
     * 段集合取并集，见 {@link #PENDING_SECTIONS}。
     */
    public static void register(LevelChunk chunk, int[] carvedSections) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        java.util.Set<Integer> sections = PENDING_SECTIONS.computeIfAbsent(keyOf(chunk),
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        // 段集合只增，所以「变大」与「有新增」等价。本方法由 farlands-gen 上的 GenTask 与服务端
        // 主线程的扫描路径两处调，两次 size 之间可能有别的线程写入，那只让判据更容易成立，方向安全。
        int before = sections.size();
        if (carvedSections != null) {
            for (int sy : carvedSections) {
                sections.add(sy);
            }
        } else {
            SectionStage.forEachStage(chunk, (sy, stage) -> {
                if (stage == SectionStage.CARVERS) {
                    sections.add(sy);
                }
            });
        }
        // 只有段集合真的变了才让门的结论作废。无条件清会让扫描路径每 tick 的重复登记把上一条记下的
        // 结论反复丢掉，那时这一项每 tick 都要重跑一次整门。
        if (sections.size() > before) {
            LAST_EVAL_EPOCH.remove(keyOf(chunk));
        }
        if (!hasDecorationPending(chunk, sections)) {
            PENDING_SECTIONS.remove(keyOf(chunk));
            return;
        }
        PENDING.put(keyOf(chunk), level);
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
        // 本轮已认领的中心：写域半径 1，两个装饰的写域相交当且仅当中心切比雪夫距离不超过 2。
        // 迭代对象是哈希集合，不能跨格步进，所以用这张小列表过滤，长度不超过本轮提交数。
        List<int[]> claimedThisRound = new ArrayList<>();
        for (Key key : new ArrayList<>(PENDING.keySet())) {
            ServerLevel level = PENDING.get(key);
            if (level == null) {
                continue;
            }
            // 跳过检查：中心已被认领即在途，认领覆盖中心自己，认领在提交前落下、收尾才撤。此时那道门
            // 必然失败，而它一次要花掉写域九格加读域外环的取数，一次查表换掉整门。
            if (DecorationClaim.isClaimed(key.dimension(), key.chunkPos())) {
                continue;
            }
            int keyCx = ChunkPos.getX(key.chunkPos());
            int keyCz = ChunkPos.getZ(key.chunkPos());
            boolean conflicts = false;
            for (int[] claimed : claimedThisRound) {
                if (Math.max(Math.abs(claimed[0] - keyCx), Math.abs(claimed[1] - keyCz)) <= 2) {
                    conflicts = true;
                    break;
                }
            }
            if (conflicts) {
                continue; // 与本轮刚认领的中心域相交：tryClaim 必失败，省下整门
            }
            // 纪元未变，说明写域九格的输入没变过，上次门的结论仍然成立，整门可省。这一条判在最前，
            // 连中心那次取数也一起省掉：它只读两个静态字段，不碰 chunk。纪元是全局单值，任意一格
            // 变化都会让全部待装饰项重判，所以这一句的次序就是本驱动层的主要成本所在。
            long epoch = cellEpoch.get();
            Long lastEval = LAST_EVAL_EPOCH.get(key);
            if (lastEval != null && lastEval.longValue() == epoch) {
                continue;
            }
            LevelChunk center = SectionLifecycle.latestChunk(level, ChunkPos.getX(key.chunkPos()),
                    ChunkPos.getZ(key.chunkPos()));
            if (center == null) {
                // 同 StructureDriver.tick：取不到不代表卸载了，丢项就再没人登记它。权威移除点是
                // clearChunk，由卸载路径调。
                continue;
            }
            if (!hasDecorationPending(center)) {
                PENDING.remove(key);
                continue;
            }
            // 中心有引用，即有结构的包围盒与它相交时，结构放置会读到 ±8；没有引用就没有结构放置，
            // 读不出写域之外，维持 ±2 省钱。
            int readRadius = center.getAllReferences().isEmpty() ? DecorationRegion.READ_RADIUS
                    : StructureDriver.STRUCTURE_READ_RADIUS;
            Map<Long, LevelChunk> handles = neighborhood(level, center, readRadius);
            if (handles == null) {
                LAST_EVAL_EPOCH.put(key, epoch); // 记下这次结论：下次格变化前不必重判
                continue; // 门没过：留表等下一轮
            }
            LAST_EVAL_EPOCH.remove(key);
            int cx = center.getPos().x();
            int cz = center.getPos().z();
            if (!DecorationClaim.tryClaim(level.dimension(), cx, cz)) {
                continue; // 写域与另一次装饰相交，留表等下一轮
            }
            if (!GenQueue.submitDecoration(new DecorationTask(level, center, key, handles, readRadius))) {
                // 池已关，即停服：撤认领、留表等下一轮
                DecorationClaim.release(level.dimension(), cx, cz);
                return;
            }
            claimedThisRound.add(new int[] { cx, cz });
        }
    }

    /** 该 level 的光照引擎。非本 port 引擎返回 null，此时装饰侧不取光照域锁。 */
    private static FarLandsLightEngine lightEngineOf(ServerLevel level) {
        return level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine engine ? engine : null;
    }

    /**
     * 写域九格的句柄与门，另加读域外环的句柄。返回 null 表示门没过：写域某格缺席、某格一个段都没有，
     * 或某格还有未过雕刻的段。
     *
     * <p>句柄在这里取好交给任务：chunk 的窗口容器与服务端的 chunk 表都不是线程安全的，池线程不得
     * 再查，所以取数是主线程的事。写域即 ±1 必须齐；读域外环到
     * {@link DecorationRegion#READ_RADIUS} 有就带上、没有就跳过，地物偶尔会读到写域外一格，缺句柄时
     * 那一次读会放弃并记日志，但门不因此拦住整遍装饰。
     *
     * <p>已有段都到 CARVERS 是写者判据：到 CARVERS 的邻居不会再被 genPool 写它自己的段，因为
     * collectSegments 只收未 TERRAIN 的段，而 surface 与 carvers 的 pending 判据都不认 CARVERS 段。
     * 有过段这一条不能省：一个段都没有的 chunk 既可能还没开始生成，也可能地形已产出但没建段，只有
     * 后者能承载装饰。
     */
    private static Map<Long, LevelChunk> neighborhood(ServerLevel level, LevelChunk center, int readRadius) {
        Map<Long, LevelChunk> handles = new HashMap<>();
        ChunkPos pos = center.getPos();
        SectionLifecycle.ChunkCursor cursor = SectionLifecycle.cursor(level);
        for (int dx = -NEIGHBORHOOD_RADIUS; dx <= NEIGHBORHOOD_RADIUS; dx++) {
            for (int dz = -NEIGHBORHOOD_RADIUS; dz <= NEIGHBORHOOD_RADIUS; dz++) {
                LevelChunk neighbor = cursor.at(pos.x() + dx, pos.z() + dz);
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
                LevelChunk neighbor = cursor.at(pos.x() + dx, pos.z() + dz);
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
        /** 本任务的读半径：由主线程按中心有无引用定好交下来，池上不再读那张表。 */
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
                // 光照域锁取在任务体开头，不在提交时：提交时取锁会把池排队的时间也算进持有期，
                // 那段时间里这一片的光照任务全被挡着。此刻一个方块都还没写，失败直接放弃即可。
                FarLandsLightEngine lightEngine = lightEngineOf(this.level);
                if (lightEngine != null
                        && !lightEngine.tryLockDomain(this.center.getPos().x(), this.center.getPos().z())) {
                    return; // 收尾照走：撤认领、清在途计数，但 region 为 null，不升段也不释放别人的锁
                }
                DecorationContext.enter();
                try {
                    region = new DecorationRegion(this.level, this.center, this.handles, this.readRadius);
                    // 结构起点的 Y 锚会经 getFirstOccupiedHeight 构造 NoiseChunk，而 NoiseChunk 的
                    // 构造点要求 TerrainSystemContext 已设，否则抛。夹法照 fill 的形状。
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
                // 下一轮的落点以当时内容为准，与放弃并重试的语义一致。
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
                // 并标明它死在取数之前还是之后：region 未赋值时收尾不会升段，症状正是装饰永远不完成。
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
     * <p>升段只认 CARVERS，即该 chunk 自己的那一段：邻居被写到的段不跟着升，否则被写到会被当成
     * 它自己的装饰完成，而那正是本档要消灭的那个混淆。
     *
     * <p>{@code region} 为 null 表示本次放弃，取数不成立或抛异常，此时只撤锁、不升段，留表重试。
     *
     * <p>不变式：删待办项的唯一位置在真的把段升成 {@code DECORATED} 之后。装饰完成的判据是段升了
     * 档，不是任务跑完了；所以放弃、收尾时取不到 chunk、没有段停在 CARVERS 这三种情形一律留项，
     * 只清纪元记录让下一轮重判门。把这三者都当成无事可做而删项时，由于登记是雕刻那一刻的一次性
     * 事件，删了没人重建，段会永久停在 CARVERS。
     */
    private static void finish(ServerLevel level, LevelChunk center, Key key, DecorationRegion region) {
        try {
            if (region == null) {
                // 本次放弃，取数不成立或没抢到光照域锁：一个方块都没写、阶段没动，段仍等着装饰。
                // 所以绝不能删待办项，登记是雕刻那一刻的一次性事件，删了就再没人重建，段会永久停在
                // CARVERS。那时走查每轮仍给它排一个生成任务，而它在三个 pending 判据上全部落空，表现为
                // 静默停摆。只清纪元记录，让下一轮重判门；光照域锁一空就会成功。
                LAST_EVAL_EPOCH.remove(key);
                return;
            }
            LevelChunk current = SectionLifecycle.latestChunk(level, center.getPos().x(), center.getPos().z());
            if (current == null) {
                // 收尾这一刻取不到 chunk，可能是瞬时取不到，也可能已不在场：没有升段就等于没完成，
                // 判据同 region == null。项留着给下一轮，只清纪元记录让门重判。
                LAST_EVAL_EPOCH.remove(key);
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
            // 下发标记：写域是九格，邻居的方块也被写过，它们各自的下发要跟着这次装饰走。与升段解耦：
            // 写过就得补发，不管本次有没有可升的段。
            for (long writtenKey : region.writtenChunks()) {
                LevelChunk owner = SectionLifecycle.latestChunk(level, ChunkPos.getX(writtenKey),
                        ChunkPos.getZ(writtenKey));
                if (owner != null) {
                    ChunkDataSender.markChunkChanged(owner);
                }
            }
            List<Integer> carvers = new ArrayList<>();
            SectionStage.forEachStage(current, (sy, stage) -> {
                if (stage == SectionStage.CARVERS) {
                    carvers.add(sy);
                }
            });
            if (carvers.isEmpty()) {
                // 没有段停在 CARVERS，说明本次没有可完成的东西：不算完成，项留给下一轮，由 tick 的无事
                // 可做分支正常移除，那个判定的归属就是它。
                LAST_EVAL_EPOCH.remove(key);
                return;
            }
            for (int sy : carvers) {
                SectionStage.setStage(current, sy, SectionStage.DECORATED);
                ((WindowedChunk) current).markSectionDirty(sy);
            }
            // 到这里才算装饰完成：三张表一起清。这是删待办项的唯一位置。
            PENDING.remove(key);
            PENDING_SECTIONS.remove(key);
            LAST_EVAL_EPOCH.remove(key);
            // 这一格的段刚从 CARVERS 升到 DECORATED：对它八个邻居的门来说，这一格的状态变了。
            cellChanged();
            // 段内容此刻才算定下来：DECORATED 到 LIGHTED 只能由光照回调推进，所以这里必须触发一次光照。
            GenQueue.notifyGenerated(current);
        } finally {
            DecorationClaim.release(level.dimension(), center.getPos().x(), center.getPos().z());
            // 只有真的取到过锁才释放。取锁失败那一支是一个方块都没写的放弃，盲目 unlock 会把别人正
            // 持有的那 25 格放掉，别人可能是光照任务，也可能是另一次装饰。
            if (region != null) {
                FarLandsLightEngine lightEngine = lightEngineOf(level);
                if (lightEngine != null) {
                    lightEngine.unlockDomain(center.getPos().x(), center.getPos().z());
                }
            }
            GenQueue.finishDecoration();
        }
    }

    /**
     * 卸载清理：该 chunk 的待装饰项、登记段与纪元记录一起丢。这是唯一的权威移除点，tick 里这一刻
     * 取不到 chunk 不构成移除理由，见那里的注释。
     */
    public static void clearChunk(ResourceKey<Level> dimension, long chunkKey) {
        Key key = new Key(dimension, chunkKey);
        PENDING.remove(key);
        PENDING_SECTIONS.remove(key);
        LAST_EVAL_EPOCH.remove(key);
    }

    /** 停服时清空待装饰表。认领表由 {@link DecorationClaim#clearWorldState()} 清。 */
    public static void clearWorldState() {
        PENDING.clear();
        PENDING_SECTIONS.clear();
        LAST_EVAL_EPOCH.clear();
        cellEpoch.incrementAndGet();
    }
}
