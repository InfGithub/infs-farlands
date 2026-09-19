package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.FarlandsConstant;
import com.inf.farlands.InfSFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.noisefiller.NoiseFiller;
import com.inf.farlands.terrain.noisefiller.OverworldNoiseFiller;
import com.inf.farlands.terrain.noisefiller.TheEndNoiseFiller;
import com.inf.farlands.terrain.noisefiller.TheNetherNoiseFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 地形管线生成任务队列。per-chunk 任务，全局并发队列，多个 worker 异步消费。
 * drainGen 仿光照 drainLight，调度单例用 CAS，任务 submit 到池。
 *
 * 每次唤醒 submit 不超过 maxGenTasksPerTick 个，宽松防风暴。唤醒源是 tick 与入队的 enqueue。
 *
 * 光照衔接按 chunk 级去重：GenTask 只推进到 NOISE，随后 notifyGenerated 触发一次 fillFrom 与
 * lightChunk。每 chunk 每批次一次，CAS 在途标志去重，不逐 section 重复播种。光照完成回调把
 * 全部 NOISE 升 LIGHTED，播种覆盖全 chunk，然后释放在途，hasAnyGen 再检查驱动下一批。
 */
public final class GenQueue {

    /**
     * fill 任务期间保持 chunk 加载的 ticket，半径 0，只保加载不卸载。
     * 与 light 的 CHUNK_WORK_TICKET 同型不同类，避免同 chunk 双 ticket 引用计数混淆。
     *
     * 26.1.2 的 TicketType 是 record，没有 mod 侧静态工厂，类型须注册进
     * BuiltInRegistries.TICKET_TYPE，且必须在 freeze 之前。注册时机由 BuiltInRegistriesMixin
     * 在 bootStrap() 头部调 ensureTicketTypeRegistered() 把本类 clinit 提前，类初始化恰好一次，
     * 因此不需要幂等守卫。
     */
    public static final TicketType GEN_WORK_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            InfSFarlands.id("gen_work"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

    /** 触发本类 clinit，即上面的注册。仅供 BuiltInRegistriesMixin 在冻结前调用。 */
    public static TicketType ensureTicketTypeRegistered() {
        return GEN_WORK_TICKET;
    }

    /** 生成任务队列，按距最近玩家距离排序，近先生成。PriorityQueue 非线程安全，用 QUEUE 自身同步。 */
    private static final PriorityQueue<GenTask> QUEUE = new PriorityQueue<>(Comparator.comparingInt(GenTask::priority));

    private static volatile OverworldNoiseFiller overworldFiller;
    private static volatile TheNetherNoiseFiller netherFiller;
    private static volatile TheEndNoiseFiller endFiller;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(genWorkerCount(), r -> {
        Thread t = new Thread(r, "farlands-gen");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean consumerActive = new AtomicBoolean();

    /** 每 chunk 光照在途标志，chunkKey 到 CAS。卸载残留接受，量小。 */
    private static final ConcurrentHashMap<Long, AtomicBoolean> LIGHT_IN_FLIGHT = new ConcurrentHashMap<>();

    /** 每 chunk 生成在途标志，chunkKey 到 CAS，同 chunk 至多一个生成任务，防并发写高度图。 */
    private static final ConcurrentHashMap<Long, AtomicBoolean> CHUNK_IN_FLIGHT = new ConcurrentHashMap<>();

    private GenQueue() {
    }

    /** 生成 worker 线程数。0 表示自动取 CPU 逻辑线程数一半，1 表示单线程，N 表示恰好 N。 */
    private static int genWorkerCount() {
        int n = FarlandsConfig.genWorkerThreads;
        if (n <= 0) {
            n = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        return n;
    }

    /** 惰性取维度 NoiseFiller，来自该维度第一个 ServerLevel。 */
    static NoiseFiller filler(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            TheNetherNoiseFiller f = netherFiller;
            if (f == null) {
                synchronized (GenQueue.class) {
                    f = netherFiller;
                    if (f == null) {
                        f = TheNetherNoiseFiller.of(level);
                        netherFiller = f;
                    }
                }
            }
            return f;
        }
        if (level.dimension() == Level.END) {
            TheEndNoiseFiller f = endFiller;
            if (f == null) {
                synchronized (GenQueue.class) {
                    f = endFiller;
                    if (f == null) {
                        f = TheEndNoiseFiller.of(level);
                        endFiller = f;
                    }
                }
            }
            return f;
        }
        OverworldNoiseFiller f = overworldFiller;
        if (f == null) {
            synchronized (GenQueue.class) {
                f = overworldFiller;
                if (f == null) {
                    f = OverworldNoiseFiller.of(level);
                    overworldFiller = f;
                }
            }
        }
        return f;
    }

    // 主线程：触发入队

    /** fill 在途保加载。ticket 操作非线程安全，调用点保证主线程。 */
    private static void addGenTicket(LevelChunk chunk) {
        ServerLevel sl = (ServerLevel) chunk.getLevel();
        sl.getChunkSource().addTicketWithRadius(GEN_WORK_TICKET, chunk.getPos(), 0);
    }

    /** genPool 线程移除 fill ticket，经 SectionIO.runOnMainThread 回主线程，异步延迟保守无害。 */
    private static void removeGenTicket(LevelChunk chunk) {
        ServerLevel sl = (ServerLevel) chunk.getLevel();
        SectionIO.runOnMainThread(
                () -> sl.getChunkSource().removeTicketWithRadius(GEN_WORK_TICKET, chunk.getPos(), 0),
                sl);
    }

    /** Y 触发：主线程上的单 section 请求。幂等，已生成不入队，入队粒度是 chunk 级任务。 */
    public static void enqueue(LevelChunk chunk, int sectionY) {
        if (SectionStage.isOrAfter(chunk, sectionY, SectionStage.NOISE)) {
            return;
        }
        // fsa 读回在途：该 section 正在从磁盘恢复，完成回调会再调 enqueue，由 isOrAfter 跳过。
        if (SectionIO.isReading(chunk.getPos().pack(), sectionY)) {
            return;
        }
        long key = chunk.getPos().pack();
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
        }
        // 已在途时不重复入队。在途任务 execute 会扫窗口并集，含此 section，execute 后新入队的
        // 由 completeTask 再检查兜底。
    }

    /**
     * XZ 触发：chunk 在主线程短路完成，处于任一玩家视距或外圈内才入队。
     * 距离判断直接按玩家坐标算，不依赖 tracking view，它每 tick 才更新，新加载 chunk 若此刻
     * 不在其中会永远错过入队。
     */
    public static void enqueueChunk(LevelChunk chunk) {
        if (!isNearPlayer(chunk)) {
            return;
        }
        long key = chunk.getPos().pack();
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
        }
    }

    /** 预加载：指定 section 范围入队，绕过 tracking view 过滤。幂等，范围内全已生成则不入队。 */
    public static void preload(LevelChunk chunk, int minSy, int maxSy) {
        boolean anyPending = false;
        for (int sy = minSy; sy <= maxSy; sy++) {
            if (sy > FarlandsConstant.MAX_CHUNK - 1 || sy < -FarlandsConstant.MAX_CHUNK) {
                continue;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.NOISE)) {
                anyPending = true;
                break;
            }
        }
        if (!anyPending) {
            return;
        }
        long key = chunk.getPos().pack();
        if (CHUNK_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            addGenTicket(chunk);
            int[] range = new int[maxSy - minSy + 1];
            for (int i = 0; i < range.length; i++) {
                range[i] = minSy + i;
            }
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk, range));
            }
            wakeConsumer();
        }
    }

    /** 该 chunk 是否在任一玩家的视距或外圈 1 内。 */
    private static boolean isNearPlayer(LevelChunk chunk) {
        Level level = chunk.getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }
        ChunkPos cp = chunk.getPos();
        for (ServerPlayer p : sl.players()) {
            ChunkTrackingView view = p.getChunkTrackingView();
            int viewDistance = view instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
            if (ChunkTrackingView.isWithinDistance(
                    p.chunkPosition().x(), p.chunkPosition().z(), viewDistance, cp.x(), cp.z(), true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * execute 完成回调：检查窗口并集内是否仍有未 NOISE 的 section，覆盖 execute 期间新入队的。
     * 有剩余就续任务并保持 CHUNK_IN_FLIGHT 为真，无剩余才清标志并释放 fill ticket。
     *
     * 标志不在续任务时清：否则会留下"标志已清、任务尚未入队"的空窗，那期间 isChunkBusy 返回假，
     * 读 section 的一方会与生成写并发。在途条目用 remove 而非 set(false)，任务链结束后条目不永存，
     * 防随探索单调增长。
     */
    static void completeTask(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        if (hasUnprocessed(chunk)) {
            synchronized (QUEUE) {
                QUEUE.add(new GenTask(chunk));
            }
            wakeConsumer();
        } else {
            CHUNK_IN_FLIGHT.remove(key);
            removeGenTicket(chunk);
        }
    }

    /** 该 chunk 在窗口并集内是否仍有未 NOISE 的 section。 */
    private static boolean hasUnprocessed(LevelChunk chunk) {
        boolean[] found = { false };
        EntitySectionWindow.forEachSectionInAnyWindow(sy -> {
            if (sy > FarlandsConstant.MAX_CHUNK - 1 || sy < -FarlandsConstant.MAX_CHUNK) {
                return;
            }
            if (!SectionStage.isOrAfter(chunk, sy, SectionStage.NOISE)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** onServerTick 每 tick 唤醒，submit 一批。 */
    public static void tick() {
        wakeConsumer();
    }

    /** 每 tick 扫描入队预算，渐进分批，不 burst。 */
    private static final int SCAN_BUDGET = 32;

    /**
     * 动态扫描：每 tick 从每个玩家当前位置螺旋向外扫描视距及外圈内的 chunk，未生成的按当前
     * 距离近先入队，生成顺序天然跟随玩家位置。budget 是 per-player 配额，多人下后遍历的玩家
     * 不会饥饿。
     */
    public static void scanAndEnqueue(MinecraftServer server) {
        int totalPlayers = 0;
        for (ServerLevel level : server.getAllLevels()) {
            totalPlayers += level.players().size();
        }
        int perPlayer = Math.max(1, SCAN_BUDGET / Math.max(1, totalPlayers));
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                int budget = perPlayer;
                ChunkPos pc = p.chunkPosition();
                ChunkTrackingView viewObj = p.getChunkTrackingView();
                int view = viewObj instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
                for (int d = 0; d <= view + 1 && budget > 0; d++) {
                    if (d == 0) {
                        if (scanChunk(level, pc.x(), pc.z())) {
                            budget--;
                        }
                        continue;
                    }
                    for (int x = -d; x <= d && budget > 0; x++) {
                        if (scanChunk(level, pc.x() + x, pc.z() - d) && --budget <= 0) {
                            break;
                        }
                        if (scanChunk(level, pc.x() + x, pc.z() + d) && --budget <= 0) {
                            break;
                        }
                    }
                    for (int z = -d + 1; z <= d - 1 && budget > 0; z++) {
                        if (scanChunk(level, pc.x() - d, pc.z() + z) && --budget <= 0) {
                            break;
                        }
                        if (scanChunk(level, pc.x() + d, pc.z() + z) && --budget <= 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 扫描单个 chunk：已加载 LevelChunk 且窗口并集内有未 NOISE 的 section，或 surface 与 carvers
     * 待处理，则 enqueueChunk，幂等。
     */
    private static boolean scanChunk(ServerLevel level, int cx, int cz) {
        ChunkAccess ca = level.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (!(ca instanceof LevelChunk lc)) {
            return false;
        }
        long key = lc.getPos().pack();
        AtomicBoolean inflight = CHUNK_IN_FLIGHT.get(key);
        if (inflight != null && inflight.get()) {
            return false;
        }
        if (!hasUnprocessed(lc)
                && !SurfaceFiller.hasSurfacePending(lc)
                && !CarverFiller.hasCarversPending(lc)) {
            return false;
        }
        enqueueChunk(lc);
        return true;
    }

    /** P2：玩家位置变化时全部在途任务按当前距离重排，修复入队快照旧。 */
    public static void rebuildQueue() {
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                return;
            }
            List<GenTask> all = new ArrayList<>(QUEUE);
            QUEUE.clear();
            for (GenTask t : all) {
                t.refreshPriority();
                QUEUE.offer(t);
            }
        }
    }

    // 光照衔接：chunk 级去重

    /** 该 chunk 是否有生成或光照任务在途，供 fsa 清理判定，在途则不清理该 chunk，保守。 */
    public static boolean isChunkBusy(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        AtomicBoolean gen = CHUNK_IN_FLIGHT.get(key);
        if (gen != null && gen.get()) {
            return true;
        }
        AtomicBoolean light = LIGHT_IN_FLIGHT.get(key);
        return light != null && light.get();
    }

    /** 报告某 section 已生成，触发光照，该 chunk 无在途光照时一次。 */
    public static void notifyGenerated(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        if (LIGHT_IN_FLIGHT.computeIfAbsent(key, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            triggerLight(chunk);
        }
    }

    /** 该 chunk 是否有光照任务在途，发包前检查，在途则留队列，等播种完成带正确光照。 */
    public static boolean isLightInFlight(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        AtomicBoolean inFlight = LIGHT_IN_FLIGHT.get(key);
        return inFlight != null && inFlight.get();
    }

    /** 一次 fillFrom 与 lightChunk，完成回调里升段、释放、再检查。 */
    private static void triggerLight(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        ServerLevel serverLevel = (ServerLevel) chunk.getLevel();
        if (serverLevel.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
            chunk.initializeLightSources();
            // fillFrom 完成，触发播种时登记等待本 chunk 的邻居重播，修正边界方向位。
            lightEngine.onChunkSkySourcesReady(chunk.getPos());
            lightEngine.lightChunk(chunk, false).whenComplete((c, t) -> {
                if (t == null) {
                    SectionStage.promoteAllGenToLighted(chunk);
                    // 光照完成，该 chunk 脏 section 入队 PERSIST，此时 fill 与光照都已完成，数据完整。
                    SectionLifecycle.persistChunkDirty(chunk);
                    // 播种完成，主动广播该 chunk 全部光照，含空 section 的 15。增量包链依赖 chunk
                    // 达 ENTITY_TICKING，而空壳先发加光照后补的管线里播种时常常未达，主动广播绕开
                    // 这个依赖，播种完成即发。
                    SectionIO.runOnMainThread(
                            () -> ChunkDataSender.broadcastChunkLight(serverLevel, chunk), serverLevel);
                } else {
                    InfSFarlands.LOGGER.error("farlands: light failed chunk={}", chunk.getPos(), t);
                }
                LIGHT_IN_FLIGHT.remove(key);
                if (SectionStage.hasAnyGen(chunk)) {
                    // 光照期间新 NOISE 未被播种覆盖，下一批。
                    notifyGenerated(chunk);
                }
            });
        } else {
            // 防御：非本 port 光引擎，释放标志防卡死，理论上不走。
            LIGHT_IN_FLIGHT.remove(key);
        }
    }

    // 关服等待

    /** 全局是否仍有生成或光照在途，任一标志为真或生成队列非空即为真。 */
    public static boolean hasInflightWork() {
        for (AtomicBoolean b : CHUNK_IN_FLIGHT.values()) {
            if (b.get()) {
                return true;
            }
        }
        for (AtomicBoolean b : LIGHT_IN_FLIGHT.values()) {
            if (b.get()) {
                return true;
            }
        }
        synchronized (QUEUE) {
            return !QUEUE.isEmpty();
        }
    }

    /**
     * 关服等待：等全局生成与光照在途收敛，有界。每轮唤醒消费，drainGen 消费不完不续唤醒，
     * 超时由 shutdownSyncFlush 的 isChunkBusy 跳过兜底。
     */
    public static void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            wakeConsumer();
            if (!hasInflightWork()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // 调度

    private static void wakeConsumer() {
        if (consumerActive.compareAndSet(false, true)) {
            try {
                POOL.submit(GenQueue::drainGen);
            } catch (RejectedExecutionException e) {
                consumerActive.set(false);
            }
        }
    }

    private static void drainGen() {
        try {
            int budget = FarlandsConfig.maxGenTasksPerTick;
            for (int i = 0; i < budget; i++) {
                GenTask task;
                synchronized (QUEUE) {
                    task = QUEUE.poll();
                }
                if (task == null) {
                    break;
                }
                POOL.submit(task::execute);
            }
        } catch (RejectedExecutionException e) {
            // 池关闭时剩余任务丢弃，daemon 不主动关，理论不触发
        } finally {
            consumerActive.set(false);
        }
    }
}
