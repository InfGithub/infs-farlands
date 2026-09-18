package com.inf.farlands.light;

import com.inf.farlands.util.hash.HashMath;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;
import com.inf.farlands.util.pos.IntBlockPos;
import com.inf.farlands.util.pos.IntSectionPos;
import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天空光引擎，子区域模型。无 Y 范围假设。
 *
 * <p>
 * 传播算法移植自 vanilla {@code SkyLightEngine}。
 *
 * <p>
 * 天空光按 256 格子区域划分，每区 16 个 section。每个活跃子区域的顶部
 * section 以 {@code DataLayer(15)} 播种。传播在子区域边界停止。
 *
 * <p>
 * 26.1.2：BlockState 光照查询去位置参数（{@code getLightDampening()}），
 * {@code LightEngine.getOcclusionShape} 只收 (state, direction)。
 */
public class FarLandsSkyLightEngine implements LayerLightEventListener {

    // ---- static constants (from vanilla SkyLightEngine) ----
    private static final long PULL_LIGHT_IN_ENTRY = LightEngine.QueueEntry.decreaseAllDirections(1);
    private static final long REMOVE_TOP_SKY_SOURCE_ENTRY = LightEngine.QueueEntry.decreaseAllDirections(15);
    private static final long REMOVE_SKY_SOURCE_ENTRY = LightEngine.QueueEntry.decreaseSkipOneDirection(15,
            Direction.UP);
    private static final long ADD_SKY_SOURCE_ENTRY = LightEngine.QueueEntry.increaseSkipOneDirection(15, false,
            Direction.UP);
    private static final Direction[] PROPAGATION_DIRECTIONS = Direction.values();

    // ---- storage ----
    final FarLandsDataLayerStorage storage;
    /** Column-key -> highest section Y that has a non-null DataLayer in storage. */
    private final ConcurrentHashMap<Long, Integer> topSections;

    // ---- propagation queues ----
    // 初始 131072，每条目 4-long，满时翻倍扩容：播种/传播每批 enqueue 巨量。
    // M1：LongRingQueue 只在 enqueue 时扩容、dequeue 不缩容 fastutil LongArrayFIFOQueue
    // expand/reduce 反复交替的扩-缩震荡 -> 分配风暴。
    private final LongRingQueue increaseQueue = new LongRingQueue(131072);
    private final LongRingQueue decreaseQueue = new LongRingQueue(131072);
    final LongOpenHashSet blockNodesToCheck = new LongOpenHashSet(512, 0.5F);
    private final Object queueLock = new Object();

    /**
     * 传播写入影响的 section，对齐 vanilla sectionsAffectedByLightUpdates，runLightUpdates
     * 末尾通知。
     */
    private final LongOpenHashSet affectedSections = new LongOpenHashSet();

    // ---- chunk access ----
    private final LightChunkGetter chunkSource;
    private final long[] lastChunkKeys = new long[2];
    private final LightChunk[] lastChunks = new LightChunk[2];

    // ---- 传播批次局部缓存：引用共享，传播 set DataLayer 数组即改 CHM，
    // 纯读加速，省 CHM.get 的 Long 装箱与 volatile 读。任务内单线程，批次入口 clear。
    // 预分配 1024，传播批次访问的 section 数可达几百，消 rehash 12.7G；fastutil clear
    // 保留数组，一次预分配复用。----
    private final Long2ObjectOpenHashMap<DataLayer> taskLocal = new Long2ObjectOpenHashMap<>(1024);

    // M3/O1：getState section 缓存用 2-slot LRU 垂直传播 src/邻居 A/B section 交替，
    // 1-slot 反复 miss 装箱 3.6G；2-slot 正好覆盖。任务入口清空 防跨任务读旧
    // section，客户端 replaceWithPacketData 换新对象；服务端 fill 原地改 section，
    // setBlockState 保持同对象引用 -> 缓存读到更新内容，安全。
    private int lastSecX1 = Integer.MIN_VALUE;
    private int lastSecY1 = Integer.MIN_VALUE;
    private int lastSecZ1 = Integer.MIN_VALUE;
    private LevelChunkSection lastSec1;
    private int lastSecX2 = Integer.MIN_VALUE;
    private int lastSecY2 = Integer.MIN_VALUE;
    private int lastSecZ2 = Integer.MIN_VALUE;
    private LevelChunkSection lastSec2;

    // P5：任务内 section 缓存，getState 2-slot miss 时查询 传播任务 section 集合有限，
    // 首访后全命中，免 CHM.get(sy) 装箱+桶查，JFR getState 22.8%。与 taskLocal 同
    // 生命周期，任务入口 clear 保留数组复用。
    private final Long2ObjectOpenHashMap<LevelChunkSection> taskSections = new Long2ObjectOpenHashMap<>(128);

    // N2：任务内 secKey 缓存，缓存 getStoredLevel/setStoredLevel 的 section key；传播链
    // 格子连续同 section，命中率高，省 hashSection 纯计算。nSec/checkNode 不缓存，
    // 因为邻居横跨/不连续，miss 时多一次比较负收益。
    private int lastKx = Integer.MIN_VALUE;
    private int lastKy = Integer.MIN_VALUE;
    private int lastKz = Integer.MIN_VALUE;
    private long lastKeyVal;

    /** N2：section key 计算 + 1-slot 缓存，同 section 连续命中省 hashSection。 */
    private long secKeyOf(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (sx == this.lastKx && sy == this.lastKy && sz == this.lastKz) {
            return this.lastKeyVal;
        }
        long k = HashMath.hash(sx, sy, sz);
        this.lastKx = sx;
        this.lastKy = sy;
        this.lastKz = sz;
        this.lastKeyVal = k;
        return k;
    }

    private DataLayer localGet(long sec) {
        DataLayer dl = taskLocal.get(sec);
        if (dl == null) {
            dl = storage.get(sec);
            if (dl != null)
                taskLocal.put(sec, dl);
        }
        return dl;
    }

    private DataLayer localGetOrCreate(long sec, long chunkKey) {
        DataLayer dl = taskLocal.get(sec);
        if (dl == null) {
            dl = storage.getOrCreate(sec, chunkKey);
            taskLocal.put(sec, dl);
        }
        return dl;
    }

    private boolean localContains(long sec) {
        return taskLocal.containsKey(sec) || storage.containsKey(sec);
    }

    // ---- empty chunk sources (vanilla L21) ----
    private final ChunkSkyLightSources emptyChunkSources;

    public FarLandsSkyLightEngine(LightChunkGetter chunkSource) {
        this(chunkSource, new FarLandsDataLayerStorage(), new ConcurrentHashMap<>());
    }

    /** 服务端共享 storage/topSections 构造，多个引擎实例共享同一仓库，供并行任务池化复用。 */
    FarLandsSkyLightEngine(LightChunkGetter chunkSource, FarLandsDataLayerStorage storage,
            ConcurrentHashMap<Long, Integer> topSections) {
        this.chunkSource = chunkSource;
        this.storage = storage;
        this.topSections = topSections;
        this.emptyChunkSources = new ChunkSkyLightSources(chunkSource.getLevel());
        Arrays.fill(lastChunkKeys, ChunkPos.INVALID_CHUNK_POS);
    }

    // ==================== 公共 API ====================

    public int getLightValue(long packedPos) {
        IntBlockPos bp = IntBlockPos.getBlockPos(packedPos);
        int cx = bp.x >> 4, sy = bp.y >> 4, cz = bp.z >> 4;
        int rx = bp.x & 15, ry = bp.y & 15, rz = bp.z & 15;

        long secKey = HashMath.hash(cx, sy, cz);
        DataLayer dl = storage.get(secKey);
        if (dl != null)
            return dl.get(rx, ry, rz);

        // 快速失败：该列最高有层 section ≤ 当前 -> sy+1..topSY 区间必无层 -> 搜索必返回 15。
        // topSections 只增不减，merge 取 max；初始无条目，null 等价旧 MIN_VALUE
        // stale 时只偏高不偏低，因为 remove 不更新 -> 快速失败结果与搜索严格一致，绝不误判。
        // CHM 弱一致读到旧值即偏低 -> 快速失败不触发 -> 走搜索 -> 结果仍正确。
        Integer top = topSections.get(HashMath.hash(cx, 0, cz));
        if (top == null || top <= sy) {
            return 15;
        }

        // 在子区域内向上搜索
        int subRegion = sy >> 4;
        int topSY = subRegion * 16 + 15;
        for (int s = sy + 1; s <= topSY; s++) {
            dl = storage.get(HashMath.hash(cx, s, cz));
            if (dl != null)
                return dl.get(rx, 0, rz); // 读层底部行，对齐 vanilla flatIndex y=0，遮挡列读 0 防幽灵亮
        }

        // 在所有数据之上或子区域未激活 虚拟天空顶
        return 15;
    }

    public void checkBlock(BlockPos pos) {
        synchronized (queueLock) {
            // P1：补注册 side-channel checkNode 反查依赖；播种不再 putBlock 注册，
            // 客户端 runLightUpdates 路径的 levelPos 无 asLong 注册，miss -> 位解码垃圾坐标。
            BlockUtil.put(HashMath.hash(pos.getX(), pos.getY(), pos.getZ()),
                    pos.getX(), pos.getY(), pos.getZ());
            blockNodesToCheck.add(HashMath.hash(pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public DataLayer getDataLayer(long sectionKey) {
        return storage.get(sectionKey);
    }

    public DataLayer getOrCreate(long sectionKey, long chunkKey) {
        DataLayer dl = storage.getOrCreate(sectionKey, chunkKey);
        IntSectionPos sp = IntSectionPos.getSectionPos(sectionKey);
        int subRegion = sp.y >> 4;
        int topSY = subRegion * 16 + 15;
        if (sp.y == topSY && dl.isEmpty()) {
            // 区顶虚拟天穹：仅当该列最低光源 <= 区顶底且露天透光到区顶时才 fill 15。
            // 最低光源在区顶之上，即该区顶被上方遮挡 -> 不 fill 深地下实心区顶被填
            // 15 -> 客户端 getLightValue 向上搜索命中区顶 15 -> 全亮，破坏时出现 15 光束。
            ChunkSkyLightSources src = getChunkSources(sp.x, sp.z);
            if (src == null) {
                dl.fill(15);
            } else {
                int lowest = src.getLowestSourceY(sp.x & 15, sp.z & 15);
                int topBase = topSY * 16;
                if (lowest != Integer.MIN_VALUE && lowest <= topBase) {
                    dl.fill(15);
                }
            }
        }
        updateTopSection(sp);
        return dl;
    }

    /** 服务端 chunk 卸载清理：side-channel 反查移除该 chunk 全部光照层，storage 不再随卸载增长。 */
    public void removeChunk(ChunkPos pos) {
        storage.removeChunk(ChunkPos.pack(pos.x(), pos.z()));
        // 清理该 column 的 topSections 条目，防随 chunk 卸载永存；remove 后 getLightValue
        // 快速失败不触发 -> 走搜索，层仍在 storage，正确。重载播种 updateTopSection re-merge。
        this.topSections.remove(HashMath.hash(pos.x(), 0, pos.z()));
        // ConcurrentHashMap 让 initializeLight 在 commonPool 异步执行与主线程的
        // setDataLayer/updateSectionStatus 并发写安全。
    }

    public void setDataLayer(long sectionKey, DataLayer layer, long chunkKey) {
        storage.put(sectionKey, layer, chunkKey);
        updateTopSection(IntSectionPos.getSectionPos(sectionKey));
    }

    /** 移除某 section 的光照层，供 fsa 清理使用。 */
    public void removeDataLayer(long sectionKey, long chunkKey) {
        storage.remove(sectionKey, chunkKey);
    }

    private void updateTopSection(IntSectionPos sp) {
        long zeroNode = HashMath.hash(sp.x, 0, sp.z);
        // 原子"只增不减"：absent -> sp.y+1；present -> max，取代原 if (cur < sp.y+1) put
        topSections.merge(zeroNode, sp.y + 1, (a, b) -> Math.max(a.intValue(), b.intValue()));
    }

    // ==================== 服务端 per-chunk 任务入口 ====================

    /**
     * 处理一个 chunk 的方块变化 + section 变化。任务独占本引擎实例，单线程执行，
     * 队列/affectedSections 在任务内消耗。先建层处理 section 变化，再 checkNode
     * 处理方块变化 checkNode 依赖所在 section 已建层。
     */
    public void processBlocksChanged(int chunkX, int chunkZ, Set<BlockPos> positions,
            Map<Integer, Boolean> sectionChanges) {
        taskLocal.clear(); // 批次入口
        clearSectionCache(); // M3 任务入口
        blockNodesToCheck.clear(); // 防池化复用残留，任务路径不经 blockNodesToCheck
        if (sectionChanges != null) {
            for (Map.Entry<Integer, Boolean> e : sectionChanges.entrySet()) {
                updateSectionStatus(SectionPos.of(chunkX, e.getKey(), chunkZ), e.getValue());
            }
        }
        for (BlockPos pos : positions) {
            // 必须用 asLong()，它会 putBlock 注册 side-channel 直接 hash 会 miss，
            // 传播链反查位解码垃圾坐标 -> 光照写错位置 -> 损坏。
            checkNode(pos.asLong());
        }
        runPropagation();
    }

    /**
     * 处理传播队列 + 通知。方块变化任务与播种任务共用 propagateLightSources 只
     * enqueue 光源，必须经本方法 propagate 才扩散；原同步模型靠每 tick runLightUpdates。
     */
    public void runPropagation() {
        taskLocal.clear(); // 批次入口；播种后传播
        clearSectionCache(); // M3 任务入口
        propagateDecreases();
        propagateIncreases();
        clearChunkCache();
        notifyLightChanges();
    }

    // ==================== runLightUpdates 支持 ====================

    @Override
    public boolean hasLightWork() {
        return !blockNodesToCheck.isEmpty() || !decreaseQueue.isEmpty() || !increaseQueue.isEmpty();
    }

    @Override
    public int runLightUpdates() {
        synchronized (queueLock) {
            taskLocal.clear(); // 批次入口
            clearSectionCache(); // M3 任务入口
            var it = blockNodesToCheck.iterator();
            while (it.hasNext())
                checkNode(it.nextLong());
            blockNodesToCheck.clear();
            blockNodesToCheck.trim(512);

            int i = 0;
            i += propagateDecreases();
            i += propagateIncreases();
            clearChunkCache();
            notifyLightChanges();
            return i;
        }
    }

    // ==================== affected-section 通知 ====================

    /** 写入影响 block 周围所有 section，对齐 vanilla setStoredLevel 的 aroundAndAtBlockPos。 */
    private void markAffected(int x, int y, int z) {
        SectionPos.aroundAndAtBlockPos(x, y, z, affectedSections::add);
    }

    /** 传播后通知，服务端走 sectionLightChanged 发增量包，客户端走 setSectionDirty 刷新渲染。 */
    private void notifyLightChanges() {
        if (affectedSections.isEmpty()) {
            return;
        }
        for (long key : affectedSections) {
            IntSectionPos sp = SectionUtil.get(key);
            if (sp == null) {
                continue; // 无 side-channel 条目，说明标记点漏 putSection -> 跳过防垃圾坐标
            }
            chunkSource.onLightUpdate(LightLayer.SKY, SectionPos.of(sp.x, sp.y, sp.z));
        }
        affectedSections.clear();
    }

    // ==================== 传播 ====================

    private int propagateIncreases() {
        int i;
        // 队列条目 4-long 即 x,y,z,entry 坐标随条目走，传播免 side-channel 反查，即 P1
        for (i = 0; increaseQueue.size() >= 4; i++) {
            int x = (int) increaseQueue.dequeueLong();
            int y = (int) increaseQueue.dequeueLong();
            int z = (int) increaseQueue.dequeueLong();
            long entry = increaseQueue.dequeueLong();
            int cur = getStoredLevel(x, y, z);
            int fromEntry = LightEngine.QueueEntry.getFromLevel(entry);
            if (LightEngine.QueueEntry.isIncreaseFromEmission(entry) && cur < fromEntry) {
                setStoredLevel(x, y, z, fromEntry);
                cur = fromEntry;
            }
            if (cur == fromEntry) {
                propagateIncrease(x, y, z, entry, cur);
            }
        }
        return i;
    }

    private int propagateDecreases() {
        int i;
        for (i = 0; decreaseQueue.size() >= 4; i++) {
            int x = (int) decreaseQueue.dequeueLong();
            int y = (int) decreaseQueue.dequeueLong();
            int z = (int) decreaseQueue.dequeueLong();
            long entry = decreaseQueue.dequeueLong();
            propagateDecrease(x, y, z, entry);
        }
        return i;
    }

    void enqueueIncrease(int x, int y, int z, long entry) {
        increaseQueue.enqueue(x);
        increaseQueue.enqueue(y);
        increaseQueue.enqueue(z);
        increaseQueue.enqueue(entry);
    }

    void enqueueDecrease(int x, int y, int z, long entry) {
        decreaseQueue.enqueue(x);
        decreaseQueue.enqueue(y);
        decreaseQueue.enqueue(z);
        decreaseQueue.enqueue(entry);
    }

    // ---- storage read/write (vanilla: getStoredLevel / setStoredLevel) ----

    private int getStoredLevel(int x, int y, int z) {
        long sec = secKeyOf(x, y, z);
        DataLayer dl = localGet(sec);
        return dl == null ? 0 : dl.get(x & 15, y & 15, z & 15);
    }

    private void setStoredLevel(int x, int y, int z, int level) {
        long sec = secKeyOf(x, y, z);
        DataLayer dl = localGetOrCreate(sec, ChunkPos.pack(x >> 4, z >> 4));
        dl.set(x & 15, y & 15, z & 15, level);
        markAffected(x, y, z);
    }

    // ---- propagateIncrease ----

    private void propagateIncrease(int srcX, int srcY, int srcZ, long queueEntry, int lightLevel) {
        BlockState blockstate = null;
        int emptyBelow = countEmptySectionsBelowIfAtBorder(srcX, srcY, srcZ);

        for (Direction dir : PROPAGATION_DIRECTIONS) {
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir))
                continue;

            // C2：内联 BlockPos.offset src 坐标直接来自队列，免反查
            int nbpX = srcX + dir.getStepX();
            int nbpY = srcY + dir.getStepY();
            int nbpZ = srcZ + dir.getStepZ();
            long nSec = HashMath.hash(nbpX >> 4, nbpY >> 4, nbpZ >> 4);
            DataLayer ndl = localGet(nSec); // 按需建层：先 get，需要写时才 getOrCreate，消除传播白建空层积累
            int nVal = ndl == null ? 0 : ndl.get(nbpX & 15, nbpY & 15, nbpZ & 15);

            int reduced = lightLevel - 1;
            if (reduced <= nVal)
                continue;

            BlockState bs1 = getState(nbpX, nbpY, nbpZ);
            int afterOpacity = lightLevel - getOpacity(bs1);
            if (afterOpacity <= nVal)
                continue;

            if (blockstate == null) {
                blockstate = LightEngine.QueueEntry.isFromEmptyShape(queueEntry)
                        ? Blocks.AIR.defaultBlockState()
                        : getState(srcX, srcY, srcZ);
            }

            // 形状检查跳过：仅当两方块都 isEmptyShape，getOcclusionShape 都返回
            // empty 且 faceShapeOccludes=false，才跳过。注意不能只跳过来源空形状
            // 目标的真实面如台阶/条件方块的 getFaceOcclusionShape 非空，会让
            // faceShapeOccludes 返回 true 即遮挡，来源空形状跳过会错误放行，光穿过台阶。
            if (!(isEmptyShape(blockstate) && isEmptyShape(bs1))) {
                if (shapeOccludes(blockstate, bs1, dir))
                    continue;
            }

            if (ndl == null) {
                ndl = localGetOrCreate(nSec, ChunkPos.pack(nbpX >> 4, nbpZ >> 4));
            }
            ndl.set(nbpX & 15, nbpY & 15, nbpZ & 15, afterOpacity);
            markAffected(nbpX, nbpY, nbpZ);
            if (afterOpacity > 1) {
                enqueueIncrease(nbpX, nbpY, nbpZ, LightEngine.QueueEntry.increaseSkipOneDirection(
                        afterOpacity, isEmptyShape(bs1), dir.getOpposite()));
            }
            propagateFromEmptySections(nbpX, nbpY, nbpZ, dir, afterOpacity, true, emptyBelow);
        }
    }

    // ---- propagateDecrease ----

    private void propagateDecrease(int srcX, int srcY, int srcZ, long lightLevel) {
        int emptyBelow = countEmptySectionsBelowIfAtBorder(srcX, srcY, srcZ);
        int fromLevel = LightEngine.QueueEntry.getFromLevel(lightLevel);

        for (Direction dir : PROPAGATION_DIRECTIONS) {
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(lightLevel, dir))
                continue;

            // C2：内联 BlockPos.offset src 坐标直接来自队列，免反查
            int nbpX = srcX + dir.getStepX();
            int nbpY = srcY + dir.getStepY();
            int nbpZ = srcZ + dir.getStepZ();
            long nSec = HashMath.hash(nbpX >> 4, nbpY >> 4, nbpZ >> 4);
            if (!localContains(nSec))
                continue;

            DataLayer ndl = localGet(nSec);
            if (ndl == null)
                continue;
            int nVal = ndl.get(nbpX & 15, nbpY & 15, nbpZ & 15);
            if (nVal == 0)
                continue;

            if (nVal <= fromLevel - 1) {
                ndl.set(nbpX & 15, nbpY & 15, nbpZ & 15, 0);
                markAffected(nbpX, nbpY, nbpZ);
                enqueueDecrease(nbpX, nbpY, nbpZ,
                        LightEngine.QueueEntry.decreaseSkipOneDirection(nVal, dir.getOpposite()));
                propagateFromEmptySections(nbpX, nbpY, nbpZ, dir, nVal, false, emptyBelow);
            } else {
                enqueueIncrease(nbpX, nbpY, nbpZ, LightEngine.QueueEntry.increaseOnlyOneDirection(
                        nVal, false, dir.getOpposite()));
            }
        }
    }

    // ---- checkNode ----

    private void checkNode(long levelPos) {
        IntBlockPos bp = IntBlockPos.getBlockPos(levelPos);
        int x = bp.x, y = bp.y, z = bp.z;
        long secKey = HashMath.hash(x >> 4, y >> 4, z >> 4);

        // vanilla 用 storage.lightOnInSection 门：无光源列即 getLowestSourceY 返回
        // MIN_VALUE，不 updateSourcesInColumn、按清光分支处理。我们无 columnsWithSources，
        // 用 hasSource 即 != MIN_VALUE 等价判断 否则 MIN_VALUE 会被 updateSourcesInColumn
        // 传给 addSourcesAbove 从 int 边界填 15，全亮火把样。
        int lowestY = getLowestSourceY(x, z);
        boolean hasSource = lowestY != Integer.MIN_VALUE;
        if (hasSource) {
            updateSourcesInColumn(x, z, lowestY);
        }

        if (localContains(secKey)) {
            if (hasSource && y >= lowestY) {
                enqueueDecrease(x, y, z, REMOVE_SKY_SOURCE_ENTRY);
                enqueueIncrease(x, y, z, ADD_SKY_SOURCE_ENTRY);
            } else {
                int cur = getStoredLevel(x, y, z);
                if (cur > 0) {
                    setStoredLevel(x, y, z, 0);
                    enqueueDecrease(x, y, z, LightEngine.QueueEntry.decreaseAllDirections(cur));
                } else {
                    enqueueDecrease(x, y, z, PULL_LIGHT_IN_ENTRY);
                }
            }
        }
    }

    // ---- countEmptySectionsBelowIfAtBorder ----

    private int countEmptySectionsBelowIfAtBorder(int x, int y, int z) {
        int relY = y & 15;
        if (relY != 0)
            return 0;

        int relX = x & 15, relZ = z & 15;
        if (relX != 0 && relX != 15 && relZ != 0 && relZ != 15)
            return 0;

        int cx = x >> 4, sy = y >> 4, cz = z >> 4;
        int subRegion = sy >> 4;
        int bottomSY = subRegion * 16;
        int count = 0;

        while (sy - count - 1 >= bottomSY
                && !localContains(HashMath.hash(cx, sy - count - 1, cz))) {
            count++;
        }
        return count;
    }

    // ---- propagateFromEmptySections ----

    private void propagateFromEmptySections(int x, int y, int z, Direction dir, int level,
            boolean increase, int emptySections) {
        if (emptySections == 0)
            return;

        if (!crossedSectionEdge(dir, x & 15, z & 15))
            return;

        int cx = x >> 4, cz = z >> 4;
        int startSY = (y >> 4) - 1;
        int endSY = startSY - emptySections + 1;

        for (int sy = startSY; sy >= endSY; sy--) {
            long secKey = HashMath.hash(cx, sy, cz);
            DataLayer dl = localGetOrCreate(secKey, ChunkPos.pack(x >> 4, z >> 4));
            int blockY = sy << 4;
            for (int ry = 15; ry >= 0; ry--) {
                // P1：不 putBlock 队列存坐标，enqueue 直接带 (x, blockY+ry, z)
                if (increase) {
                    dl.set(x & 15, ry, z & 15, level);
                    markAffected(x, blockY + ry, z);
                    if (level > 1) {
                        enqueueIncrease(x, blockY + ry, z, LightEngine.QueueEntry.increaseSkipOneDirection(
                                level, true, dir.getOpposite()));
                    }
                } else {
                    dl.set(x & 15, ry, z & 15, 0);
                    markAffected(x, blockY + ry, z);
                    enqueueDecrease(x, blockY + ry, z, LightEngine.QueueEntry.decreaseSkipOneDirection(
                            level, dir.getOpposite()));
                }
            }
        }
    }

    // ---- static helpers ----

    private static boolean crossedSectionEdge(Direction dir, int rx, int rz) {
        return switch (dir) {
            case NORTH -> rz == 15;
            case SOUTH -> rz == 0;
            case WEST -> rx == 15;
            case EAST -> rx == 0;
            default -> false;
        };
    }

    private static boolean isSourceLevel(int level) {
        return level == 15;
    }

    private static boolean isEmptyShape(BlockState state) {
        return !state.canOcclude() || !state.useShapeForLightOcclusion();
    }

    // ==================== 天空光源 ====================

    private int getLowestSourceY(int x, int z) {
        ChunkSkyLightSources src = getChunkSources(x >> 4, z >> 4);
        return src == null ? Integer.MAX_VALUE : src.getLowestSourceY(x & 15, z & 15);
    }

    private ChunkSkyLightSources getChunkSources(int cx, int cz) {
        LightChunk lc = getChunk(cx, cz);
        return lc == null ? null : lc.getSkyLightSources();
    }

    private void updateSourcesInColumn(int x, int z, int lowestY) {
        int subRegion = (lowestY >> 4) >> 4;
        int bottomSY = subRegion * 16;
        removeSourcesBelow(x, z, lowestY, bottomSY);
        addSourcesAbove(x, z, lowestY, bottomSY);
    }

    // ---- removeSourcesBelow ----

    private void removeSourcesBelow(int x, int z, int minY, int bottomSectionY) {
        if (minY <= bottomSectionY)
            return;
        int cx = x >> 4, cz = z >> 4;
        int limitY = minY - 1;

        for (int sy = limitY >> 4; sy >= bottomSectionY; sy--) {
            long secKey = HashMath.hash(cx, sy, cz);
            if (!storage.containsKey(secKey))
                continue;

            DataLayer dl = storage.get(secKey);
            if (dl == null)
                continue;
            affectedSections.add(SectionPos.asLong(cx, sy, cz)); // 进入即标记，含提前 return 分支
            int baseY = sy << 4;
            int topY = Math.min(baseY + 15, limitY);

            for (int by = topY; by >= baseY; by--) {
                // P1：不 putBlock enqueue 直接带坐标 (x, by, z)
                if (!isSourceLevel(dl.get(x & 15, by - baseY, z & 15)))
                    return;
                dl.set(x & 15, by - baseY, z & 15, 0);
                enqueueDecrease(x, by, z, by == minY - 1 ? REMOVE_TOP_SKY_SOURCE_ENTRY : REMOVE_SKY_SOURCE_ENTRY);
            }
        }
    }

    // ---- addSourcesAbove ----

    private void addSourcesAbove(int x, int z, int maxY, int bottomSectionY) {
        int cx = x >> 4, cz = z >> 4;
        int neighMin = Math.max(
                Math.max(getLowestSourceY(x - 1, z), getLowestSourceY(x + 1, z)),
                Math.max(getLowestSourceY(x, z - 1), getLowestSourceY(x, z + 1)));
        int effectiveMax = Math.max(maxY, bottomSectionY << 4);

        int subRegion = (maxY >> 4) >> 4;
        int topSY = subRegion * 16 + 15;

        for (int sy = effectiveMax >> 4; sy <= topSY; sy++) {
            long secKey = HashMath.hash(cx, sy, cz);
            if (!storage.containsKey(secKey))
                continue;

            DataLayer dl = storage.get(secKey);
            if (dl == null)
                continue;
            affectedSections.add(SectionPos.asLong(cx, sy, cz)); // 进入即标记，含提前 return 分支
            int baseY = sy << 4;
            int topY = baseY + 15;

            // long 化防溢出：topY = baseY + 15 在 2.14B 即 sy=MAX_CHUNK 时 = Integer.MAX_VALUE，
            // int by++ 溢出为负 -> 无限循环 -> DataLayer.get 越界 CTD。
            for (long by = Math.max((long) baseY, (long) effectiveMax); by <= topY; by++) {
                // P1：不 putBlock enqueue 直接带坐标 (x, by, z)
                if (isSourceLevel(dl.get(x & 15, (int) by - baseY, z & 15)))
                    return;
                dl.set(x & 15, (int) by - baseY, z & 15, 15);
                if (by < neighMin || by == maxY) {
                    enqueueIncrease(x, (int) by, z, ADD_SKY_SOURCE_ENTRY);
                }
            }
        }
    }

    // ---- setLightEnabled ----

    @Override
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        if (isEmpty) {
            // 不删层：同 BlockLightEngine checkNode 的 decrease 传播需要层在，
            // 立即删层会导致相邻 section 光残留。空层随 chunk 卸载由 removeChunk 清理。
        } else {
            getOrCreate(pos.asLong(), chunkKey);
        }
    }

    @Override
    public DataLayer getDataLayerData(SectionPos pos) {
        return storage.get(pos.asLong());
    }

    @Override
    public int getLightValue(BlockPos pos) {
        return getLightValue(pos.asLong());
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enabled) {
        if (!enabled)
            return;
        int cx = pos.x(), cz = pos.z();
        ChunkSkyLightSources sources = java.util.Objects.requireNonNullElse(
                getChunkSources(cx, cz), emptyChunkSources);
        // 无光源即 getHighestLowestSourceY 返回 MIN_VALUE，如未 fillFrom 的 LevelChunk
        // -> 无区顶 15 否则 MIN_VALUE-1 溢出成 int max -> 遍历垃圾 section 建层 fill 15。
        int highestLowest = sources.getHighestLowestSourceY();
        if (highestLowest == Integer.MIN_VALUE)
            return;
        highestLowest -= 1;
        int startSY = (highestLowest >> 4) + 1;

        int subRegion = startSY >> 4;
        int bottomSY = subRegion * 16;
        int topSY = subRegion * 16 + 15;

        for (int sy = topSY - 1; sy >= Math.max(bottomSY, startSY); sy--) {
            long secKey = HashMath.hash(cx, sy, cz);
            DataLayer dl = storage.getOrCreate(secKey, ChunkPos.pack(cx, cz));
            if (dl.isEmpty()) {
                dl.fill(15);
            }
        }
    }

    // ---- propagateLightSources ----

    /** 该 chunk 所有已建 section 是否全空，纯空气即全露天。 */
    private static boolean isAllAir(WindowedChunk wc) {
        for (LevelChunkSection s : wc.windowedAllSections().values()) {
            if (s != null && !s.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }

    public void propagateLightSources(ChunkPos pos) {
        synchronized (queueLock) {
            propagateLightSourcesLocked(pos, false);
        }
    }

    /**
     * 重播专用，即 R1：只播 XZ 边界列，rx/rz in {0,15} 邻居 fillFrom 后方向位修正
     * 只影响边界列，内部列邻居是本 chunk 内部，未变；边界列传播自然扩散内部。
     */
    public void propagateLightSourcesBoundary(ChunkPos pos) {
        synchronized (queueLock) {
            propagateLightSourcesLocked(pos, true);
        }
    }

    /**
     * 邻居边界列光源高度分三态：未加载/空气即 fillFrom 完成无源 -> MAX_VALUE，穿透，
     * 方向位恒 true；fill 中即未 fillFrom -> MIN_VALUE，保守，方向位 false，等邻居
     * fillFrom 后触发重播修正；有源 -> 实际 lowest。
     */
    private int neighborLowest(int cx, int cz, int localX, int localZ) {
        LightChunk lc = getChunk(cx, cz);
        if (lc == null)
            return Integer.MAX_VALUE; // 未加载 = 空气
        ChunkSkyLightSources src = lc.getSkyLightSources();
        if (src == null)
            return Integer.MIN_VALUE; // 已加载未 initialize = fill 中，保守
        if (src instanceof ISkyLightSourcesAccessor acc && !acc.farlands$isFillFromDone()) {
            return Integer.MIN_VALUE; // fill 中，保守
        }
        int lowest = src.getLowestSourceY(localX, localZ);
        return lowest == Integer.MIN_VALUE ? Integer.MAX_VALUE : lowest; // 空气 -> 穿透
    }

    /**
     * 播种读邻居前登记"本 chunk 等 4 邻居"，邻居 fillFrom 后触发重播修正边界。
     * 无条件登记，含已 fillFrom 就绪的邻居：就绪邻居的 fillFrom 后续变化，如新
     * section 生成，同样需要重播本 chunk。
     */
    private void registerSkyWaiters(int cx, int cz) {
        long selfKey = ChunkPos.pack(cx, cz);
        FarLandsLightEngine.registerSkyWaiter(ChunkPos.pack(cx, cz - 1), selfKey);
        FarLandsLightEngine.registerSkyWaiter(ChunkPos.pack(cx, cz + 1), selfKey);
        FarLandsLightEngine.registerSkyWaiter(ChunkPos.pack(cx - 1, cz), selfKey);
        FarLandsLightEngine.registerSkyWaiter(ChunkPos.pack(cx + 1, cz), selfKey);
    }

    private void propagateLightSourcesLocked(ChunkPos pos, boolean boundaryOnly) {
        int cx = pos.x(), cz = pos.z();

        var lc = getChunk(cx, cz);
        if (lc instanceof WindowedChunk wc) {
            if (isAllAir(wc)) {
                // 纯空气 = 全露天：天空光全 15，无遮挡无衰减 vanilla 引擎对
                // 无方块世界不播种，ChunkSkyLightSources 无光源 MIN_VALUE，
                // 此处特判：全空 chunk 直接填 15 并通知 affectedSections
                // -> 服务端增量包 -> 客户端 queueSectionData(15) -> 亮。
                for (int sy : wc.windowedAllSections().keySet()) {
                    long secKey = HashMath.hash(cx, sy, cz);
                    DataLayer dl = storage.getOrCreate(secKey, ChunkPos.pack(cx, cz));
                    dl.fill(15);
                    affectedSections.add(SectionPos.asLong(cx, sy, cz));
                }
                return;
            }
            for (int sy : wc.windowedAllSections().keySet()) {
                int subRegion = sy >> 4;
                int topSY = subRegion * 16 + 15;
                if (sy == topSY) {
                    long secKey = HashMath.hash(cx, sy, cz);
                    // 注册 side-channel：getOrCreate 区顶逻辑内部反查 sp 依赖此条目，
                    // 否则 fallback 位解码垃圾坐标 -> 区顶 fill 15 判断错误。
                    SectionUtil.put(secKey, cx, sy, cz);
                    getOrCreate(secKey, ChunkPos.pack(cx, cz));
                }
            }
        }

        // 播种读 4 邻居边界列光源 fill 中邻居即未 fillFrom，方向位保守 -> 边界短暂黑；
        // 登记"本 chunk 等邻居"，邻居 fillFrom 后触发重播修正
        registerSkyWaiters(cx, cz);

        setLightEnabled(pos, true);

        ChunkSkyLightSources s0 = java.util.Objects.requireNonNullElse(getChunkSources(cx, cz), emptyChunkSources);

        int bx = cx << 4, bz = cz << 4;

        lc = getChunk(cx, cz);
        if (lc instanceof WindowedChunk wc2) {
            for (int sy : wc2.windowedAllSections().keySet()) {
                long secKey = HashMath.hash(cx, sy, cz);
                // vanilla 语义：getDataLayerToWrite 创建层，传播初始化必须建层，
                // 否则地表空层被跳过 -> 最低光源处永不 enqueue -> 传播无源 -> 全黑。
                // 空层即全 0 留在 storage 也无害：该位置本就被遮挡，该黑。
                DataLayer dl = storage.getOrCreate(secKey, ChunkPos.pack(cx, cz));
                affectedSections.add(SectionPos.asLong(cx, sy, cz)); // 每 section 一次，渲染刷新粒度

                int baseY = sy << 4;
                int maxY = baseY + 15;

                for (int rx = 0; rx < 16; rx++) {
                    for (int rz = 0; rz < 16; rz++) {
                        // R1：重播只播边界列，方向位修正只影响边界，内部列邻居是本 chunk
                        if (boundaryOnly && rx != 0 && rx != 15 && rz != 0 && rz != 15) {
                            continue;
                        }
                        int lowest0 = s0.getLowestSourceY(rx, rz);
                        if (lowest0 == Integer.MIN_VALUE) {
                            // 露天列：天空直射 -> 全 15；只 enqueue 边界格（B 优化）。
                            int lowestN = rz == 0 ? neighborLowest(cx, cz - 1, rx, 15)
                                    : s0.getLowestSourceY(rx, rz - 1);
                            int lowestS = rz == 15 ? neighborLowest(cx, cz + 1, rx, 0)
                                    : s0.getLowestSourceY(rx, rz + 1);
                            int lowestW = rx == 0 ? neighborLowest(cx - 1, cz, 15, rz)
                                    : s0.getLowestSourceY(rx - 1, rz);
                            int lowestE = rx == 15 ? neighborLowest(cx + 1, cz, 0, rz)
                                    : s0.getLowestSourceY(rx + 1, rz);
                            // L2：露天/未加载邻居，MIN=本 chunk 无源，MAX=跨 chunk 未加载/空气。
                            // L3：down 剪枝 下方 section 在本 chunk 播种循环内。
                            boolean nOpen = lowestN == Integer.MIN_VALUE || lowestN == Integer.MAX_VALUE;
                            boolean sOpen = lowestS == Integer.MIN_VALUE || lowestS == Integer.MAX_VALUE;
                            boolean wOpen = lowestW == Integer.MIN_VALUE || lowestW == Integer.MAX_VALUE;
                            boolean eOpen = lowestE == Integer.MIN_VALUE || lowestE == Integer.MAX_VALUE;
                            boolean belowInSow = wc2.windowedAllSections().containsKey(sy - 1);
                            for (int by = maxY; by >= baseY; by--) {
                                dl.set(rx, by - baseY, rz, 15);
                                boolean down = by == baseY && !belowInSow;
                                boolean north = !nOpen && by < lowestN;
                                boolean south = !sOpen && by < lowestS;
                                boolean west = !wOpen && by < lowestW;
                                boolean east = !eOpen && by < lowestE;
                                if (down || north || south || west || east) {
                                    // P1：不 putBlock enqueue 直接带坐标，播种格不入 blockLookup
                                    enqueueIncrease(bx + rx, by, bz + rz,
                                            LightEngine.QueueEntry.increaseSkySourceInDirections(
                                                    down, north, south, west, east));
                                }
                            }
                            continue;
                        }
                        // 地表在更高处即 lowest0 > maxY -> 本 section 该列由上方传播给光，不播种
                        if (lowest0 > maxY)
                            continue;

                        int lowestN = rz == 0 ? neighborLowest(cx, cz - 1, rx, 15) : s0.getLowestSourceY(rx, rz - 1);
                        int lowestS = rz == 15 ? neighborLowest(cx, cz + 1, rx, 0) : s0.getLowestSourceY(rx, rz + 1);
                        int lowestW = rx == 0 ? neighborLowest(cx - 1, cz, 15, rz) : s0.getLowestSourceY(rx - 1, rz);
                        int lowestE = rx == 15 ? neighborLowest(cx + 1, cz, 0, rz) : s0.getLowestSourceY(rx + 1, rz);
                        // L2：同露天列分支 露天/未加载邻居水平位 false + 不参与 neighborMin；
                        // atLowest 即 by==lowest0、地表向下进地下，保留。
                        boolean nOpen = lowestN == Integer.MIN_VALUE || lowestN == Integer.MAX_VALUE;
                        boolean sOpen = lowestS == Integer.MIN_VALUE || lowestS == Integer.MAX_VALUE;
                        boolean wOpen = lowestW == Integer.MIN_VALUE || lowestW == Integer.MAX_VALUE;
                        boolean eOpen = lowestE == Integer.MIN_VALUE || lowestE == Integer.MAX_VALUE;

                        for (int by = maxY; by >= Math.max(baseY, lowest0); by--) {
                            dl.set(rx, by - baseY, rz, 15);
                            boolean atLowest = by == lowest0;
                            boolean north = !nOpen && by < lowestN;
                            boolean south = !sOpen && by < lowestS;
                            boolean west = !wOpen && by < lowestW;
                            boolean east = !eOpen && by < lowestE;
                            if (atLowest || north || south || west || east) {
                                // P1：不 putBlock enqueue 直接带坐标
                                enqueueIncrease(bx + rx, by, bz + rz,
                                        LightEngine.QueueEntry.increaseSkySourceInDirections(
                                                atLowest, north, south, west, east));
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== chunk 缓存 ====================

    private LightChunk getChunk(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        for (int i = 0; i < 2; i++) {
            if (key == lastChunkKeys[i])
                return lastChunks[i];
        }
        LightChunk lc = chunkSource.getChunkForLighting(cx, cz);
        lastChunkKeys[1] = lastChunkKeys[0];
        lastChunks[1] = lastChunks[0];
        lastChunkKeys[0] = key;
        lastChunks[0] = lc;
        return lc;
    }

    private void clearChunkCache() {
        Arrays.fill(lastChunkKeys, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(lastChunks, null);
    }

    // ==================== 方块访问 ====================

    /** M3/O1/P5：任务入口清 section 缓存，包括 2-slot 与任务内缓存，防跨任务读旧 section。 */
    private void clearSectionCache() {
        this.lastSecX1 = Integer.MIN_VALUE;
        this.lastSecY1 = Integer.MIN_VALUE;
        this.lastSecZ1 = Integer.MIN_VALUE;
        this.lastSec1 = null;
        this.lastSecX2 = Integer.MIN_VALUE;
        this.lastSecY2 = Integer.MIN_VALUE;
        this.lastSecZ2 = Integer.MIN_VALUE;
        this.lastSec2 = null;
        this.taskSections.clear();
        this.lastKx = Integer.MIN_VALUE; // N2：secKey 缓存哨兵重置
    }

    /**
     * M3/O1：坐标版 getState + 2-slot section 缓存 传播调用点坐标已知，省 mutablePos +
     * CHM.get 装箱。null section -> BEDROCK 语义与 getChunk null -> BEDROCK 一致。
     */
    private BlockState getState(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        LevelChunkSection s;
        if (sx == this.lastSecX1 && sy == this.lastSecY1 && sz == this.lastSecZ1) {
            s = this.lastSec1;
        } else if (sx == this.lastSecX2 && sy == this.lastSecY2 && sz == this.lastSecZ2) {
            s = this.lastSec2;
        } else {
            LightChunk lc = getChunk(sx, sz);
            if (lc == null) {
                return Blocks.BEDROCK.defaultBlockState();
            }
            // P5：先查任务内 section 缓存，fastutil 无装箱 CHM.get 只剩任务内首访
            long secKey = HashMath.hash(sx, sy, sz);
            s = this.taskSections.get(secKey);
            if (s == null) {
                s = lc instanceof WindowedChunk wc ? wc.windowedAllSections().get(sy) : null;
                if (s != null) { // 不缓存 null section，fastutil get null 有歧义且 null section 极少
                    this.taskSections.put(secKey, s);
                }
            }
            // LRU：旧 slot1 降级 slot2，新值进 slot1
            this.lastSecX2 = this.lastSecX1;
            this.lastSecY2 = this.lastSecY1;
            this.lastSecZ2 = this.lastSecZ1;
            this.lastSec2 = this.lastSec1;
            this.lastSecX1 = sx;
            this.lastSecY1 = sy;
            this.lastSecZ1 = sz;
            this.lastSec1 = s;
        }
        if (s != null) {
            // N1：列掩码快路径 该行无方块即空气 -> 直接 AIR 常量，免 palette 查找。
            short[] masks = ((IColumnMasks) s).farlands$columnMasksIfReady();
            if (masks != null && (masks[(x & 15) + (z & 15) * 16] & (1 << (y & 15))) == 0) {
                return Blocks.AIR.defaultBlockState();
            }
            return s.getBlockState(x & 15, y & 15, z & 15);
        }
        return Blocks.BEDROCK.defaultBlockState();
    }

    private int getOpacity(BlockState state) {
        return Math.max(1, state.getLightDampening());
    }

    private boolean shapeOccludes(BlockState state1, BlockState state2, Direction dir) {
        VoxelShape shape1 = LightEngine.getOcclusionShape(state1, dir);
        VoxelShape shape2 = LightEngine.getOcclusionShape(state2, dir.getOpposite());
        return Shapes.faceShapeOccludes(shape1, shape2);
    }
}
