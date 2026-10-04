package com.inf.farlands.terrain.decorationFiller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.debug.StageMetrics;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.terrainFiller.TerrainSystemContext;
import com.inf.farlands.util.map.Long2ObjectStripedMap;
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
    private static final Map<Key, Set<Integer>> PENDING_SECTIONS = new ConcurrentHashMap<>();

    /**
     * 放弃原因只打前若干条。门没过的项每轮都会重试，不限流会把日志刷爆；形状与 SectionLifecycle 的
     * ENCODE_FAIL_LOGGED 相同。
     */
    private static final AtomicInteger ABORT_LOGGED = new AtomicInteger();

    /**
     * 每 chunk 一个变化计数，键是 chunk 坐标。
     *
     * <p>必须按 chunk 记而不能全局记：门只取决于该项写域九格的状态，全局单值时任意一格变化都让 PENDING
     * 里全部项作废门结论、重跑整门，于是门的总代价随待办表规模线性放大。
     */
    private static final Long2ObjectStripedMap<AtomicLong> CHUNK_EPOCH =
            new Long2ObjectStripedMap<>(1 << 12);

    /**
     * 该 chunk 的变化计数，未 bump 过按 0 计。
     *
     * <p>只读不建条目，而不是 computeIfAbsent：读路径不该改结构，而条目由 {@link #cellChanged} 在写侧建。
     */
    private static long chunkEpoch(long chunkKey) {
        AtomicLong v = CHUNK_EPOCH.get(chunkKey);
        return v == null ? 0L : v.get();
    }

    /**
     * 写域九格的变化计数之和。
     *
     * <p>取和而不是取最大：某个 chunk 被 bump 时，若它不是邻域里的最大值，最大值不变，于邻域含它的项
     * 不会重判，那个变化被吞掉。和是单调不减且任一格递增必增的，不会有这个缺口。
     */
    private static long neighborhoodEpoch(int chunkX, int chunkZ) {
        long sum = 0L;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sum += chunkEpoch(ChunkPos.pack(chunkX + dx, chunkZ + dz));
            }
        }
        return sum;
    }

    /** 每项上次评估门时的纪元与帧号。纪元相同且未过保鲜期则跳过整门。 */
    private static final Map<Key, Eval> LAST_EVAL_EPOCH = new ConcurrentHashMap<>();

    /**
     * 一次门评估的结论：当时的纪元、当时的帧号、以及门是否通过。
     *
     * <p>通过与否决定保鲜期取哪一个：门没过要等格变化，取长保鲜期；门过了却没能提交，只是等一个
     * 认领空出来，取短保鲜期。两类都不改变门看到的输入，所以都该在保鲜期内不再重判。
     */
    private record Eval(long epoch, long frame, boolean passed) {
    }

    /**
     * 门通过的项的重试间隔，单位帧。
     *
     * <p>门过了却没提交成，只是在等一个认领空出来，而认领的持有期是毫秒量级，所以这一档取短保鲜期。
     * 取 8 是持有期的上界：再短不会有额外收益，因为保鲜期一缩，重判次数必然上升，而提交量不会因此回升。
     */
    private static final int CLAIM_RETRY_FRAMES = 8;

    /** 门评估的帧号。只由主线程写，见 {@link #tick()}。 */
    private static long frame;

    /**
     * 门失败结论的保鲜期，单位帧。
     *
     * <p>只按纪元判「结论仍成立」有一个缺口：纪元只在格变化时加一，而系统一旦静下来就没有格变化，
     * 于是同一纪元下的失败结论永不过期，停在门上的项再也不会被重判，停摆因此自我维持。本上限让结论
     * 在不超期时仍然照省，超期则强制重判一次。
     *
     * <p>取值依据：超期重判的代价是「全部待办项各跑一轮门」，而这一档的间隔决定了那个代价的频率；
     * 取 200 帧时，它在主线程驱动占比里的增量远小于活跃期的量级。
     */
    private static final int STALE_FRAMES = 200;

    /**
     * 复现开关：为真时 {@link #cellChanged} 不 bump {@code CHUNK_EPOCH}，于是邻域和恒为 0，
     * 「纪元变了」这条重判通路被整条吞掉。
     *
     * <p>它用来复现一条自锁：推进依赖「格变化」信号，而信号又依赖推进。默认关闭，关闭时行为与不带本
     * 开关逐位相同。
     */
    private static final boolean PROBE_SWALLOW_EPOCH = false;

    /**
     * 某一格的状态变了，受影响的是该 chunk。主线程与 genPool 都调，触发点是存在流程建壳、GenTask 推到
     * CARVERS、读回落段、收尾升段、卸载。
     *
     * <p>按 chunk 记而不是全局记：门的依赖面只到写域九格，全局记会让任意一格变化作废全部项的门结论。
     */
    public static void cellChanged(long chunkKey) {
        if (PROBE_SWALLOW_EPOCH) {
            // 只 bump 全局计数器，而没有任何地方读它：邻域和因此恒 0。
            SWALLOWED_EPOCH.incrementAndGet();
            return;
        }
        CHUNK_EPOCH.computeIfAbsent(chunkKey, k -> new AtomicLong()).incrementAndGet();
    }

    /** 复现开关打开时的落点，只为让 bump 有副作用，不为任何逻辑服务。 */
    private static final AtomicLong SWALLOWED_EPOCH = new AtomicLong();

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

    private static boolean hasDecorationPending(LevelChunk chunk, Set<Integer> recorded) {
        Set<Integer> sections = recorded;
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
        Set<Integer> sections = PENDING_SECTIONS.computeIfAbsent(keyOf(chunk),
                k -> ConcurrentHashMap.newKeySet());
        // 门结论只在【本次真的新增了段】时作废。判据取 Set.add 的返回值，不取 size 的前后比较：
        // 集合只增，第一次登记之后 size 恒不变，于是「size 变大」改判的写法在后续登记里恒假，记录被
        // 反复丢掉，那一项每帧都要重跑整门，而门一次是 25 格取数加九次段表遍历。本方法由 farlands-gen
        // 上的 GenTask 与服务端主线程的扫描路径两处调，两处都按同一判据。
        boolean added = false;
        if (carvedSections != null) {
            for (int sy : carvedSections) {
                added |= sections.add(sy);
            }
        } else {
            boolean[] a = { false };
            SectionStage.forEachStage(chunk, (sy, stage) -> {
                if (stage == SectionStage.CARVERS) {
                    a[0] |= sections.add(sy);
                }
            });
            added = a[0];
        }
        if (added) {
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
        frame++;
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
            // 纪元未变且未过保鲜期，说明写域九格的输入没变过，上次门的结论仍然成立，整门可省。这一条
            // 判在最前，连中心那次取数也一起省掉：它只读两个静态字段，不碰 chunk。
            Eval lastEval = LAST_EVAL_EPOCH.get(key);
            // 邻域和只在需要比较时才算：lastEval 为 null 时判据第一项就短路，那九次取数的结果用不上。
            // 而没有评估记录的项很多（每次 register 加了新段就 remove 一次记录），所以这一条次序
            // 省掉的是「无论如何都用不上」的九次带乐观戳的取数。
            if (lastEval != null) {
                long epoch = neighborhoodEpoch(keyCx, keyCz);
                // 保鲜期按门是否通过分流：没过要等格变化，过了只是在等认领空出来。
                if (lastEval.epoch() == epoch
                        && frame - lastEval.frame() < (lastEval.passed() ? CLAIM_RETRY_FRAMES : STALE_FRAMES)) {
                    continue;
                }
            }
            // 落门的项要 epoch 来写记录，所以在落门时再算一次。落门的是少数（每项每窗约十次），
            // 而上面那一支省掉的是「lastEval 为 null 时无论如何都用不上」的九次取数。
            long epoch = neighborhoodEpoch(keyCx, keyCz);
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
                LAST_EVAL_EPOCH.put(key, new Eval(epoch, frame, false)); // 门没过：等格变化，取长保鲜期
                continue; // 门没过：留表等下一轮
            }
            // 门过了也记：认领或提交可能在下一步失败，那时若这里删掉记录，该项下一帧又要重跑整门。
            // 认领失败不改变门看到的任何输入，所以短保鲜期成立。
            LAST_EVAL_EPOCH.put(key, new Eval(epoch, frame, true));
            int cx = center.getPos().x();
            int cz = center.getPos().z();
            if (!DecorationClaim.tryClaim(level.dimension(), cx, cz)) {
                continue; // 写域与另一次装饰相交，留表等下一轮
            }
            DecorationTask task = new DecorationTask(level, center, key, handles, readRadius);
            if (!GenQueue.submitDecoration(task)) {
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
            // 持有者编号在外层声明，finally 才捕获得到 try 内赋的值。取域失败时保持 0，收尾不释放。
            long lockOwner = 0L;
            try {
                // 光照域锁取在任务体开头，不在提交时：提交时取锁会把池排队的时间也算进持有期，
                // 那段时间里这一片的光照任务全被挡着。此刻一个方块都还没写，失败直接放弃即可。
                FarLandsLightEngine lightEngine = lightEngineOf(this.level);
                if (lightEngine != null) {
                    lockOwner = lightEngine.tryLockDomain(this.center.getPos().x(), this.center.getPos().z());
                    if (lockOwner == 0L) {
                        return; // 收尾照走：撤认领、清在途计数，lockOwner 为 0 不释放任何格
                    }
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
                // 分流：region 为 null 时本次一个方块都没写，没有任何主线程专属的收尾工作，收尾就在池
                // 线程内做完，让认领早释放。认领挡的是照光侧，而它在主线程队列里等一笔空的收尾时，照光
                // 池就被按住了。
                //
                // 带锁收尾必须回主线程：装方块实体、升段、标脏、补发、触发光照五件都要求主线程，而它们
                // 只在真的装饰过、即 region 非 null 时才存在。
                DecorationRegion computed = region;
                long computedOwner = lockOwner;
                if (computed == null) {
                    finishRegionNull(this.level, this.center, this.key, computedOwner);
                } else {
                    SectionIO.runOnMainThread(
                            () -> finish(this.level, this.center, this.key, computed, computedOwner), this.level);
                }
            }
        }
    }

    /**
     * 没写成任何东西的那一支的收尾。在 farlands-gen 上执行，不走主线程。
     *
     * <p>为什么可以不走主线程：这一支一个方块都没写，所以没有方块实体要装、没有段要升、没有下发要
     * 补、没有光照要触，而主线程专属的工作只有那四件。留下的三件，撤纪元、撤认领、清在途计数，
     * 三件都有并发安全的载体。
     *
     * <p>为什么必须不走主线程：认领挡的是照光侧的取域，而收尾走主线程时认领要在主线程队列里排到这一
     * 笔被执行才撤，于是持有期被拉长到「池队列等待加收尾」的量级；而这一支一个方块都没写，占提交数的
     * 九成上下，等于用一笔空收尾按住照光池。
     *
     * <p>顺序是契约：先撤纪元，再撤认领。反过来的话，池线程撤完认领、还没撤纪元之前的窗口里，主线程
     * 的 tick 可能重复认领同一中心并提交第二次任务，而本方法随后的 remove 会擦掉第二次尝试刚设下的
     * 纪元。后果只是多重判一次门，但把顺序固定下来就没有这个窗口。
     */
    private static void finishRegionNull(ServerLevel level, LevelChunk center, Key key, long lockOwner) {
        // 取域锁失败：一个方块都没写，门看到的输入没变，所以不该抹掉记录，抹掉会让该项下一帧重跑
        // 整门。只把「门通过」的标记翻回假，让主线程按短保鲜期重试。帧号原样沿用主线程那一次的读值，
        // 本方法不做算术。
        Eval prev = LAST_EVAL_EPOCH.get(key);
        if (prev != null) {
            LAST_EVAL_EPOCH.put(key, new Eval(prev.epoch(), prev.frame(), false));
        }
        DecorationClaim.release(level.dimension(), center.getPos().x(), center.getPos().z());
        if (lockOwner != 0L) {
            FarLandsLightEngine lightEngine = lightEngineOf(level);
            if (lightEngine != null) {
                lightEngine.unlockDomain(center.getPos().x(), center.getPos().z(), lockOwner);
            }
        }
        GenQueue.finishDecoration();
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
    private static void finish(ServerLevel level, LevelChunk center, Key key, DecorationRegion region,
            long lockOwner) {
        // region 为 null 的那一支已在 finishRegionNull 里于池线程收尾，本方法只收「真的装饰过」的，
        // 所以这里的 region 必定非 null。
        try {
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
            cellChanged(current.getPos().pack());
            // 段内容此刻才算定下来：DECORATED 到 LIGHTED 只能由光照回调推进，所以这里必须触发一次光照。
            GenQueue.notifyGenerated(current);
        } finally {
            DecorationClaim.release(level.dimension(), center.getPos().x(), center.getPos().z());
            // 只有真的取到过锁才释放：取域返回的持有者编号非 0 才算取到。取锁失败那一支是一个方块
            // 都没写的放弃，把 0 传下去不会碰到任何别人持有的格。
            if (lockOwner != 0L) {
                FarLandsLightEngine lightEngine = lightEngineOf(level);
                if (lightEngine != null) {
                    lightEngine.unlockDomain(center.getPos().x(), center.getPos().z(), lockOwner);
                }
            }
            GenQueue.finishDecoration();
        }
    }

    /**
     * 卸载清理：该 chunk 的待装饰项、登记段与纪元记录一起丢。这是唯一的权威移除点，tick 里这一刻
     * 取不到 chunk 不构成移除理由，见那里的注释。
     *
     * <p>变化计数不在这里删：{@code SectionLifecycle.flushChunk} 在调本方法之后会补一次
     * {@link #cellChanged}，删了也会被立刻重建。留着一个旧计数无害，它只让邻域的和偏高，而含该格的
     * 项本来就该重判。
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
        CHUNK_EPOCH.clear();
    }
}
