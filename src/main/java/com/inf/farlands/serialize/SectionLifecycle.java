package com.inf.farlands.serialize;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * fsa 生命周期层。负责清理判定、编码队列、批调度、内存管理与读回。
 *
 * encode 一律在主线程。静止假设会与窗口内 section 冲突，这里用主线程串行代替静止。
 * 待编码队列 pendingEncode 用 ConcurrentLinkedDeque，卸载线程 flushChunk 入队，主线程
 * tick 消费，只存 chunk、sectionY 与 mode。编码时现取最新状态，因此等待期间发生的
 * setBlock 与光照变化都会被编码捕捉，而编码时刻与主线程写串行，没有竞态。
 *
 * 每 tick 的 tick() 在预算 256 内 drain。现取，encode，按文件与 mode 分组 prepare，
 * 再提交 IO 写。
 * CLEANUP 即清理。commit 后删内存，同时删 section、stage、dirty 与光照层。
 * PERSIST 即定期或卸载。commit 后清 dirty，不删内存。
 * encode 或写失败则不 commit，dirty 保留，等下次重试。
 *
 * 四个触发点。cleanup 在窗口变化时把边界外的脏 section 入队 CLEANUP。flushChunk 在卸载
 * 时把脏 section 入队 PERSIST。flushAllDirty 周期性地把所有脏 section 入队 PERSIST。
 * shutdownSyncFlush 关服同步兜底。
 *
 * 26.1.2 相对 1.21.1 的差异，均已 javap 打运行时 jar 核实。
 * ChunkMap.getChunks 在 26.1.2 不存在，public 遍历入口只剩 forEachReadyToSendChunk，
 * 而它不是全量。改用反射读 ChunkMap.visibleChunkMap，类型是
 * Long2ObjectLinkedOpenHashMap 装 ChunkHolder，语义等价于旧的 getChunks，即全部可见
 * ChunkHolder。ChunkHolder.getLatestChunk 声明在父类 GenerationChunkHolder 上，
 * 26.1.2 仍在。
 * stage 由 SectionStage 承载，取值 0 UNPROCESSED、1 BIOMES、2 NOISE、3 SURFACE、4
 * CARVERS、
 * 5 LIGHTED。旧仓库是 NeoForge attachment，且与 terrain 共用。
 * terrain 侧调用收口在 TerrainHooks，即 GenQueue.isChunkBusy 与 GenQueue.enqueueChunk。
 * 条目的 block_states 与 biomes codec 从 chunk 的 PalettedContainerFactory 取。
 * ChunkPos.toLong 改名 pack，cp.x 与 cp.z 改成 cp.x() 与 cp.z()。
 * chunk.getSectionIndexFromSectionY 来自 LevelHeightAccessor 而非 WindowedChunk，
 * LevelChunk 继承之。
 */
public final class SectionLifecycle {

    /** 每 tick 清理入队上限。 */
    public static final int CLEANUP_BUDGET = 1024;
    /** 每 tick 主线程 encode 上限，分摊预算，防卡 tick。 */
    public static final int ENCODE_BUDGET = 256;

    /** 待编码单元。只存引用，编码时现取最新，主线程串行因此无竞态。mode 是 cleanup 语义。 */
    private record EncodeUnit(LevelChunk chunk, int sectionY, boolean cleanup) {
    }

    /** commit 后动作的单位。cleanup 删内存，persist 清 dirty。 */
    private record CleanupUnit(LevelChunk chunk, int sectionY) {
    }

    /** 待编码队列。卸载线程入队，主线程 tick 消费，因此线程安全。 */
    private static final ConcurrentLinkedDeque<EncodeUnit> pendingEncode = new ConcurrentLinkedDeque<>();

    /**
     * 窗口未建立时加载的 chunk。loadChunkSections 遇到 windowSy 为空就标记延迟读回，等
     * 窗口建立后由 retryPendingReads 重新触发，否则磁盘数据在但重进漏读会让 section 消失。
     * 强引用，生命周期短，窗口建立后 1 tick 内清空。
     */
    private static final Set<LevelChunk> pendingWindowRead = ConcurrentHashMap.newKeySet();

    /** 26.1.2 没有 ChunkMap.getChunks()，改反射 visibleChunkMap。 */
    private static final Field F_VISIBLE_CHUNKS;

    static {
        try {
            F_VISIBLE_CHUNKS = ChunkMap.class.getDeclaredField("visibleChunkMap");
            F_VISIBLE_CHUNKS.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SectionLifecycle() {
    }

    // ---- 触发入队 ----

    /** 窗口变化时扫描清理，主线程。由 FarlandsTick 在窗口差量为真时调用。 */
    public static void cleanup(MinecraftServer server) {
        int[] budget = { CLEANUP_BUDGET };
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                // 用 getLatestChunk 而非 getTickingChunk。暂停或登出后 chunk 会降级，
                // tickingChunkFuture 完成成 UNLOADED，getTickingChunk 返回 null 就漏遍历。
                // getLatestChunk 不依赖 ticking 状态，只要 chunk 在 holder 里就返回。
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc) || TerrainHooks.isChunkBusy(lc)) {
                    continue;
                }
                WindowedChunk wc = (WindowedChunk) lc;
                // 增量扫描，只遍历窗口并集加余量之外的 section，成本是 O(log n + 边界外数)
                wc.forEachOutsideWindows(FarlandsConfig.fsaCleanupMargin, sy -> {
                    if (budget[0] <= 0) {
                        return;
                    }
                    if (wc.isSectionDirty(sy)) {
                        pendingEncode.add(new EncodeUnit(lc, sy, true));
                        budget[0]--;
                    } else {
                        removeFromMemory(lc, sy);
                    }
                });
            }
        }
    }

    /** 卸载编码在途任务数，关服等待用，任务出口递减。 */
    private static final AtomicInteger ENCODE_TASKS_IN_FLIGHT = new AtomicInteger();

    /**
     * 卸载编码独立线程池，单线程 daemon。与 genPool 隔离，避免编码抢生成线程。探索时
     * fill 与卸载编码曾共享 genPool，结果 fill 被延迟，生成效率下降。
     */
    private static final ExecutorService ENCODE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "farlands-encode");
        t.setDaemon(true);
        return t;
    });

    /**
     * 卸载时写该 chunk 的全部脏 section，由 ServerLevel.unload 之前调用。
     *
     * 生成或光照在途即 isChunkBusy 时跳过，半成品与未就绪光照不落盘。
     *
     * 编码提交到独立的 ENCODE_POOL，不占主线程预算、不阻塞卸载线程、不与生成抢线程。
     * flushChunk 返回后 LevelChunk 引用即释放，闭包只被编码任务短暂持有。并发约定：
     * cleanup 在主线程可能并发删除本 chunk 窗口外的 section，编码读到 null 或旧引用都无害，
     * 因为 cleanup 只删磁盘已有或已入队的 section，同一 section 的重复写也幂等。
     *
     * 编码任务出口顺手 SectionStage.clear。stage 载体是静态 map，chunk 卸载后必须清，
     * 否则随探索单调增长。
     */
    public static void flushChunk(LevelChunk lc) {
        if (TerrainHooks.isChunkBusy(lc)) {
            return;
        }
        ServerLevel level = (ServerLevel) lc.getLevel();
        ENCODE_TASKS_IN_FLIGHT.incrementAndGet();
        try {
            ENCODE_POOL.submit(() -> {
                try {
                    encodeAndSubmit(lc, level);
                } finally {
                    ENCODE_TASKS_IN_FLIGHT.decrementAndGet();
                    SectionStage.clear(lc);
                }
            });
        } catch (RejectedExecutionException e) {
            ENCODE_TASKS_IN_FLIGHT.decrementAndGet();
            // 池关闭，编码丢弃，数据由重进重生成兜底
        }
    }

    /**
     * 编码池线程：现取现编码该 chunk 的全部脏 section，再回主线程 prepareWrite 与
     * submitWrite。提交前验证 chunk 对象未变，若已重新加载为新对象就丢弃该编码，数据交给
     * 新 chunk 的写盘路径，防旧数据覆盖新数据。验证对象为 null 或仍是同一对象都提交。
     */
    private static void encodeAndSubmit(LevelChunk lc, ServerLevel level) {
        ChunkPos cp = lc.getPos();
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        // path 到 (entry, slotIdx, sy)
        Map<Path, List<Object[]>> byFile = new LinkedHashMap<>();
        for (Integer sy : ((WindowedChunk) lc).windowedAllSections().keySet()) {
            if (!((WindowedChunk) lc).isSectionDirty(sy)) {
                continue;
            }
            byte[] entry = encodeNow(lc, sy, factory);
            if (entry == null) {
                continue;
            }
            Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
            byFile.computeIfAbsent(path, k -> new ArrayList<>())
                    .add(new Object[] { entry, SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), sy });
        }
        if (byFile.isEmpty()) {
            return;
        }
        SectionIO.runOnMainThread(() -> {
            ChunkAccess ca = level.getChunk(cp.x(), cp.z(), ChunkStatus.FULL, false);
            if (ca != null && ca != lc) {
                return; // 已重新加载为新对象，丢弃旧编码
            }
            for (Map.Entry<Path, List<Object[]>> e : byFile.entrySet()) {
                Path path = e.getKey();
                SectionStorage st = SectionIO.getOrOpen(path);
                List<SectionStorage.PendingWrite> batch = new ArrayList<>();
                for (Object[] meta : e.getValue()) {
                    SectionStorage.PendingWrite pw = st.prepareWrite((Integer) meta[1], (byte[]) meta[0]);
                    if (pw != null) {
                        batch.add(pw);
                    }
                }
                if (!batch.isEmpty()) {
                    // 卸载路径的 onAllDone 只 commit，不 clearDirty。chunk 已释放，
                    // dirty 随对象消亡。
                    SectionIO.submitWrite(level, path, batch, () -> {
                        SectionStorage st2 = SectionIO.getOrOpen(path);
                        for (SectionStorage.PendingWrite pw : batch) {
                            st2.commitWrite(pw);
                        }
                    });
                }
            }
        }, level);
    }

    /**
     * 关服：等卸载编码任务全部提交，有界。ENCODE_POOL 执行编码，再由 runOnMainThread 提交
     * prepareWrite 与 submitWrite，因此循环内要反复 drain 主线程来消费提交回调。超时后
     * 未提交的编码丢弃，由重进重生成兜底。必须在 awaitIODrain 之前调用，否则编码任务提交的
     * IO 写不会被等待。
     */
    public static void awaitEncodeTasks(MinecraftServer server, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && ENCODE_TASKS_IN_FLIGHT.get() > 0) {
            SectionIO.drainMainThreadTasks(server);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 光照完成后的补触发，把该 chunk 的脏 section 入队 PERSIST。此时 fill 与光照都已完成，
     * 数据完整。调用点保证不 busy，因此无条件入队，幂等。调用点是 GenQueue.triggerLight 的
     * lightChunk whenComplete。
     */
    public static void persistChunkDirty(LevelChunk lc) {
        enqueueDirty(lc);
    }

    /** 该 chunk 的全部脏 section 入队 PERSIST。只存引用，主线程 tick 现取现编码。 */
    private static void enqueueDirty(LevelChunk lc) {
        WindowedChunk wc = (WindowedChunk) lc;
        for (Integer sy : wc.windowedAllSections().keySet()) {
            if (wc.isSectionDirty(sy)) {
                pendingEncode.add(new EncodeUnit(lc, sy, false));
            }
        }
    }

    /**
     * 周期持久化，每 fsaPersistInterval tick 一次。主线程把全部已加载 chunk 的脏 section
     * 入队 PERSIST。
     */
    public static void flushAllDirty(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                if (TerrainHooks.isChunkBusy(lc)) {
                    continue; // 生成或光照在途，跳过，改由光照完成补触发 persistChunkDirty 写盘
                }
                WindowedChunk wc = (WindowedChunk) lc;
                for (Integer sy : wc.windowedAllSections().keySet()) {
                    if (wc.isSectionDirty(sy)) {
                        pendingEncode.add(new EncodeUnit(lc, sy, false));
                    }
                }
            }
        }
    }

    // ---- 每 tick 编码消费：主线程 ----

    /** 每 tick 在预算内 drain。现取，encode，prepare，再按文件与 mode 分组提交写。 */
    public static void tick() {
        Map<Path, List<SectionStorage.PendingWrite>> cleanupByFile = new LinkedHashMap<>();
        Map<Path, List<CleanupUnit>> cleanupUnits = new LinkedHashMap<>();
        Map<Path, List<SectionStorage.PendingWrite>> persistByFile = new LinkedHashMap<>();
        Map<Path, List<CleanupUnit>> persistUnits = new LinkedHashMap<>();
        Map<Path, ServerLevel> levelByPath = new HashMap<>();

        int budget = ENCODE_BUDGET;
        while (budget-- > 0) {
            EncodeUnit u = pendingEncode.poll();
            if (u == null) {
                break;
            }
            PalettedContainerFactory factory = ((WindowedChunk) u.chunk()).containerFactory();
            byte[] entry = encodeNow(u.chunk(), u.sectionY(), factory);
            if (entry == null) {
                continue; // encode 失败，丢弃，dirty 保留，下次触发会重入队
            }
            ServerLevel level = (ServerLevel) u.chunk().getLevel();
            ChunkPos cp = u.chunk().getPos();
            Path path = SectionIO.filePath(level, cp.x(), cp.z(), u.sectionY());
            SectionStorage st = SectionIO.getOrOpen(path);
            SectionStorage.PendingWrite pw = st.prepareWrite(
                    SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, u.sectionY() & 31), entry);
            if (pw == null) {
                continue;
            }
            levelByPath.put(path, level);
            if (u.cleanup()) {
                cleanupByFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                cleanupUnits.computeIfAbsent(path, k -> new ArrayList<>())
                        .add(new CleanupUnit(u.chunk(), u.sectionY()));
            } else {
                persistByFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                persistUnits.computeIfAbsent(path, k -> new ArrayList<>())
                        .add(new CleanupUnit(u.chunk(), u.sectionY()));
            }
        }
        submitBatches(levelByPath, cleanupByFile, cleanupUnits, true);
        submitBatches(levelByPath, persistByFile, persistUnits, false);
    }

    /**
     * 关服同步兜底，把仍脏的 section 同步 encode、prepare、doWrite、commit 并同步刷盘。
     * 调用方已 awaitIODrain，IO 队列为空，再加上 drainMainThreadTasks 让 commit 回调执行完，
     * 因此本方法只处理屏障之后新标脏的，比较罕见。全部在主线程同步完成，不依赖异步回调。
     */
    public static void shutdownSyncFlush(MinecraftServer server) {
        Map<Path, List<SectionStorage.PendingWrite>> byFile = new LinkedHashMap<>();
        Map<Path, List<CleanupUnit>> unitsByFile = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : getChunks(level)) {
                // 用 getLatestChunk 而非 getTickingChunk。关服或暂停后 chunk 降级为非 TICKING，
                // getTickingChunk 返回 null 就漏写盘，这是数据丢的根因。getLatestChunk 不依赖
                // ticking。
                ChunkAccess ca = holder.getLatestChunk();
                if (!(ca instanceof LevelChunk lc)) {
                    continue;
                }
                if (TerrainHooks.isChunkBusy(lc)) {
                    continue; // 关服等待超时后仍有在途，跳过，重进重生成兜底
                }
                WindowedChunk wc = (WindowedChunk) lc;
                PalettedContainerFactory factory = wc.containerFactory();
                for (Integer sy : wc.windowedAllSections().keySet()) {
                    if (!wc.isSectionDirty(sy)) {
                        continue;
                    }
                    byte[] entry = encodeNow(lc, sy, factory); // PERSIST 语义
                    if (entry == null) {
                        continue;
                    }
                    ChunkPos cp = lc.getPos();
                    Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
                    SectionStorage st = SectionIO.getOrOpen(path);
                    SectionStorage.PendingWrite pw = st.prepareWrite(
                            SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), entry);
                    if (pw != null) {
                        byFile.computeIfAbsent(path, k -> new ArrayList<>()).add(pw);
                        unitsByFile.computeIfAbsent(path, k -> new ArrayList<>())
                                .add(new CleanupUnit(lc, sy));
                    }
                }
            }
        }
        // 同步写，即主线程直接 file.write，再 commit 与清 dirty
        for (Map.Entry<Path, List<SectionStorage.PendingWrite>> e : byFile.entrySet()) {
            Path path = e.getKey();
            SectionStorage st = SectionIO.getOrOpen(path);
            try {
                st.doWrite(e.getValue());
            } catch (Exception ex) {
                InfsFarlands.LOGGER.error("fsa shutdown write failed {}", path, ex);
                continue;
            }
            for (SectionStorage.PendingWrite pw : e.getValue()) {
                st.commitWrite(pw);
            }
            for (CleanupUnit u : unitsByFile.get(path)) {
                ((WindowedChunk) u.chunk()).clearSectionDirty(u.sectionY());
            }
        }
        SectionIO.flushAllSync();
    }

    /**
     * 提交写批。onAllDone 按 mode 决定后续动作，cleanup 是 commit 后删内存，
     * persist 是 commit 后清 dirty。
     */
    private static void submitBatches(Map<Path, ServerLevel> levelByPath,
            Map<Path, List<SectionStorage.PendingWrite>> byFile,
            Map<Path, List<CleanupUnit>> unitsByFile, boolean cleanup) {
        for (Map.Entry<Path, List<SectionStorage.PendingWrite>> e : byFile.entrySet()) {
            Path path = e.getKey();
            List<SectionStorage.PendingWrite> batch = e.getValue();
            List<CleanupUnit> units = unitsByFile.get(path);
            SectionIO.submitWrite(levelByPath.get(path), path, batch, () -> {
                SectionStorage st = SectionIO.getOrOpen(path);
                for (SectionStorage.PendingWrite pw : batch) {
                    st.commitWrite(pw);
                }
                if (units != null) {
                    for (CleanupUnit u : units) {
                        if (cleanup) {
                            removeFromMemory(u.chunk(), u.sectionY());
                        } else {
                            ((WindowedChunk) u.chunk()).clearSectionDirty(u.sectionY());
                        }
                    }
                }
            });
        }
    }

    private static final AtomicInteger ENCODE_FAIL_LOGGED = new AtomicInteger();

    /**
     * 现取现编码单个 section，等待期间的变化会被捕捉。主线程 tick 与编码池卸载共用，
     * 只做纯读加局部对象，任意线程安全。编码时刻与主线程写串行，没有竞态。
     */
    private static byte[] encodeNow(LevelChunk lc, int sy, PalettedContainerFactory factory) {
        try {
            LevelChunkSection section = ((WindowedChunk) lc).windowedAllSections().get(sy);
            if (section == null) {
                return null;
            }
            ServerLevel level = (ServerLevel) lc.getLevel();
            LevelLightEngine le = level.getChunkSource().getLightEngine();
            DataLayer bl = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(lc.getPos(), sy));
            DataLayer sl = le.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(lc.getPos(), sy));
            int stage = SectionStage.getStage(lc, sy);
            return SectionSerializer.encode(section, bl, sl, stage, factory, sy);
        } catch (Exception e) {
            // encode 失败就静默返回，数据不写盘也就是丢失，但 dirty 保留，之后重试
            if (ENCODE_FAIL_LOGGED.getAndIncrement() < 20) {
                InfsFarlands.LOGGER.info("ENCODE-FAIL chunk={},{} sy={} err={}",
                        lc.getPos().x(), lc.getPos().z(), sy, e.toString());
            }
            return null;
        }
    }

    /** 删内存，包括 section、stage、脏标记与光照层，主线程执行。窗口内的不删。 */
    private static void removeFromMemory(LevelChunk lc, int sy) {
        if (EntitySectionWindow.inAnyWindow(sy)) {
            return;
        }
        WindowedChunk wc = (WindowedChunk) lc;
        wc.windowedAllSections().remove(sy);
        wc.removeActiveSection(sy);
        SectionStage.removeStage(lc, sy);
        wc.clearSectionDirty(sy);
        if (lc.getLevel() instanceof ServerLevel sl
                && sl.getChunkSource().getLightEngine() instanceof FarLandsLightEngine fle) {
            SectionPos pos = SectionPos.of(lc.getPos(), sy);
            fle.removeSectionData(LightLayer.BLOCK, pos);
            fle.removeSectionData(LightLayer.SKY, pos);
        }
    }

    // ---- 读回 ----

    /**
     * chunk 加载后读回窗口内的 section，主线程执行，全部完成后跑 onDone，随后可入生成队列。
     *
     * 调用点是 terrain 的 chunk 短路链，即 GenerationChunkHolderMixin 的 existence flow：
     * 建空壳，后台填 biome，回主线程调 loadChunkSections，完成后 enqueueChunk。单 section 读回
     * 走 loadSection，由窗口滑入触发。
     */
    public static void loadChunkSections(LevelChunk lc, Runnable onDone) {
        ServerLevel level = (ServerLevel) lc.getLevel();
        ChunkPos cp = lc.getPos();
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        List<Integer> windowSy = new ArrayList<>();
        EntitySectionWindow.forEachSectionInAnyWindow(windowSy::add);
        if (windowSy.isEmpty()) {
            // 窗口未建立。重进瞬间 ranges 为空就标记延迟读回，等窗口建立后由 retryPendingReads
            // 重新触发。onDone 照常执行，此时生成侧收集到的窗口为空，不会生成，等待重试。
            pendingWindowRead.add(lc);
            SectionIO.unmarkReadingBatch(lc, windowSy);
            onDone.run();
            return;
        }
        pendingWindowRead.remove(lc); // 读回已发起，幂等，可能本来就没标记过
        SectionIO.markReadingBatch(lc, windowSy);

        Map<Path, List<SectionStorage.SlotRef>> byFile = new LinkedHashMap<>();
        Map<Path, ServerLevel> levelByPath = new HashMap<>();
        for (int sy : windowSy) {
            Path path = SectionIO.filePath(level, cp.x(), cp.z(), sy);
            if (!Files.exists(path)) {
                continue; // 无文件即无数据，不创建
            }
            SectionStorage st = SectionIO.getOrOpen(path);
            SectionStorage.SlotRef ref = st.getSlot(
                    SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sy & 31), sy);
            if (ref != null) {
                byFile.computeIfAbsent(path, k -> new ArrayList<>()).add(ref);
                levelByPath.put(path, level);
            }
        }
        if (byFile.isEmpty()) {
            SectionIO.unmarkReadingBatch(lc, windowSy);
            onDone.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(byFile.size());
        for (Map.Entry<Path, List<SectionStorage.SlotRef>> e : byFile.entrySet()) {
            SectionIO.submitRead(levelByPath.get(e.getKey()), e.getKey(), e.getValue(), factory,
                    decodedList -> {
                        for (SectionIO.DecodedWithSy d : decodedList) {
                            applyDecoded(lc, d.sectionY(), d.decoded());
                        }
                        if (pending.decrementAndGet() <= 0) {
                            SectionIO.unmarkReadingBatch(lc, windowSy);
                            skySourcesReady(lc);
                            onDone.run();
                        }
                    });
        }
    }

    /**
     * 每 tick 由 FarlandsTick 调用，重试那些加载时窗口还没建立的 chunk 读回。窗口已建立就
     * 重新 loadChunkSections，此时 windowSy 非空，会读回窗口内 section，包含重进瞬间漏读的。
     * 窗口仍为空则 loadChunkSections 内部会重新标记，下 tick 再试。已卸载则数据已落盘，
     * 不再处理。
     */
    public static void retryPendingReads(MinecraftServer server) {
        if (pendingWindowRead.isEmpty() || EntitySectionWindow.ranges().length == 0) {
            return; // 无 pending 或窗口未建立，即 Preparing 与玩家未注册期，零开销早退。
                    // 此前 ranges 为空也全量重试，导致初次进入世界每 tick 巨量 stat 卡死。
        }
        int budget = 32; // 每 tick 最多重试 32 个，防单 tick 巨量 Files.exists stat
        for (LevelChunk lc : pendingWindowRead) {
            if (budget-- <= 0) {
                break; // 剩余留到下轮，pending 不减少，下轮继续
            }
            pendingWindowRead.remove(lc); // 先移除防重入，windowSy 仍空会在 loadChunkSections 内重新标记
            if (lc.getLevel() instanceof ServerLevel) {
                loadChunkSections(lc, () -> TerrainHooks.enqueueGen(lc));
            }
        }
    }

    /**
     * 窗口滑入单个 section 的读回，主线程执行，完成后跑 onDone，随后可入生成队列。
     * 调用点是 ChunkDataSender.enqueueForWindow，即窗口差量检测。
     */
    public static void loadSection(LevelChunk lc, int sectionY, Runnable onDone) {
        SectionIO.markReading(lc, sectionY);
        ServerLevel level = (ServerLevel) lc.getLevel();
        ChunkPos cp = lc.getPos();
        Path path = SectionIO.filePath(level, cp.x(), cp.z(), sectionY);
        boolean exist = Files.exists(path);
        if (!exist) {
            SectionIO.unmarkReading(lc, sectionY);
            onDone.run();
            return;
        }
        SectionStorage st = SectionIO.getOrOpen(path);
        SectionStorage.SlotRef ref = st.getSlot(
                SectionStorage.slotIndex(cp.x() & 31, cp.z() & 31, sectionY & 31), sectionY);
        if (ref == null) {
            SectionIO.unmarkReading(lc, sectionY);
            onDone.run();
            return;
        }
        PalettedContainerFactory factory = ((WindowedChunk) lc).containerFactory();
        SectionIO.submitRead(level, path, List.of(ref), factory, decodedList -> {
            SectionIO.unmarkReading(lc, sectionY);
            for (SectionIO.DecodedWithSy d : decodedList) {
                applyDecoded(lc, d.sectionY(), d.decoded());
            }
            skySourcesReady(lc);
            onDone.run();
        });
    }

    /**
     * 读回完成时触发。清理 SKY_WAITERS 里等待该 chunk 的条目，并触发邻居边界重播。
     * 读回的 chunk 里 SkyLightSources 未 fillFrom，source 全为 minY，于是 boundaryAllOpen
     * 为 false，会触发重播。重播用的伪源与播种时一致，不新增错误。读回每 chunk 一次，
     * 属低频。
     */
    private static void skySourcesReady(LevelChunk lc) {
        if (lc.getLevel() instanceof ServerLevel sl
                && sl.getChunkSource().getLightEngine() instanceof FarLandsLightEngine fle) {
            fle.onChunkSkySourcesReady(lc.getPos());
        }
    }

    /**
     * 读回结果应用，主线程。写入数据与光照，恢复 stage，并补发 section 包。读回不标脏，
     * 因为磁盘上已经有了。
     */
    private static void applyDecoded(LevelChunk lc, int sy, SectionSerializer.DecodedSection decoded) {
        WindowedChunk wc = (WindowedChunk) lc;
        wc.windowedAllSections().put(sy, decoded.section());
        wc.addActiveSection(sy);
        lc.getSection(lc.getSectionIndexFromSectionY(sy)); // 数组同步，get 内部会执行 arr[idx]=s
        if (lc.getLevel() instanceof ServerLevel sl) {
            LevelLightEngine le = sl.getChunkSource().getLightEngine();
            SectionPos pos = SectionPos.of(lc.getPos(), sy);
            if (decoded.blockLight() != null) {
                le.queueSectionData(LightLayer.BLOCK, pos, decoded.blockLight());
            }
            if (decoded.skyLight() != null) {
                le.queueSectionData(LightLayer.SKY, pos, decoded.skyLight());
            }
        }
        SectionStage.setStage(lc, sy, decoded.stage());
        ChunkDataSender.enqueueSectionSend(lc, sy);
    }

    // ---- 工具 ----

    @SuppressWarnings("unchecked")
    private static Iterable<ChunkHolder> getChunks(ServerLevel level) {
        try {
            Object visible = F_VISIBLE_CHUNKS.get(level.getChunkSource().chunkMap);
            return ((Long2ObjectLinkedOpenHashMap<ChunkHolder>) visible).values();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
