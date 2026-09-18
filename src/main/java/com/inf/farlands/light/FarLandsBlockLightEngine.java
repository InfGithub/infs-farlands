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
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 方块光引擎。传播移植自 vanilla {@code BlockLightEngine}。
 *
 * <p>
 * 单层 {@link FarLandsDataLayerStorage} 替代 vanilla 的 queued/updating/visible
 * 三层缓冲。
 *
 * <p>
 * 26.1.2：BlockState 光照查询去位置参数（{@code getLightEmission()} /
 * {@code getLightDampening()}），{@code LightEngine.getOcclusionShape} 只收
 * (state, direction)。
 */
public class FarLandsBlockLightEngine implements LayerLightEventListener {

    private static final long PULL_LIGHT_IN_ENTRY = LightEngine.QueueEntry.decreaseAllDirections(1);
    private static final Direction[] PROPAGATION_DIRECTIONS = Direction.values();

    final FarLandsDataLayerStorage storage;
    // M1：LongRingQueue 只在 enqueue 时扩容、dequeue 不缩容
    private final LongRingQueue increaseQueue = new LongRingQueue(131072);
    private final LongRingQueue decreaseQueue = new LongRingQueue(131072);
    final LongOpenHashSet blockNodesToCheck = new LongOpenHashSet(512, 0.5F);
    private final Object queueLock = new Object();

    /**
     * 传播写入影响的 section，对齐 vanilla sectionsAffectedByLightUpdates，runLightUpdates
     * 末尾通知。
     */
    private final LongOpenHashSet affectedSections = new LongOpenHashSet();

    private final LightChunkGetter chunkSource;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final long[] lastChunkKeys = new long[2];
    private final LightChunk[] lastChunks = new LightChunk[2];

    // ---- 传播批次局部缓存：引用共享，纯读加速，省 CHM 装箱。任务内单线程。
    // 预分配 1024，消 rehash；clear 保留数组复用。----
    private final Long2ObjectOpenHashMap<DataLayer> taskLocal = new Long2ObjectOpenHashMap<>(1024);

    // getState section 缓存用 2-slot LRU，垂直传播 A/B section 交替，1-slot 反复
    // miss 装箱；2-slot 覆盖。任务入口清空，在 taskLocal.clear 处。
    private int lastSecX1 = Integer.MIN_VALUE;
    private int lastSecY1 = Integer.MIN_VALUE;
    private int lastSecZ1 = Integer.MIN_VALUE;
    private LevelChunkSection lastSec1;
    private int lastSecX2 = Integer.MIN_VALUE;
    private int lastSecY2 = Integer.MIN_VALUE;
    private int lastSecZ2 = Integer.MIN_VALUE;
    private LevelChunkSection lastSec2;

    // 任务内 section 缓存，getState 2-slot miss 时查询，传播任务 section 集合有限，
    // 首访后全命中，免 CHM.get(sy) 装箱+桶查。与 taskLocal 同生命周期，任务入口 clear。
    private final Long2ObjectOpenHashMap<LevelChunkSection> taskSections = new Long2ObjectOpenHashMap<>(128);

    // 任务内 secKey 缓存，缓存 getStoredLevel/setStoredLevel 的 section key
    // 传播链格子连续同 section，命中率高，省 hashSection 纯计算。
    private int lastKx = Integer.MIN_VALUE;
    private int lastKy = Integer.MIN_VALUE;
    private int lastKz = Integer.MIN_VALUE;
    private long lastKeyVal;

    /** section key 计算 + 1-slot 缓存，同 section 连续命中省 hashSection。 */
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

    public FarLandsBlockLightEngine(LightChunkGetter chunkSource) {
        this(chunkSource, new FarLandsDataLayerStorage());
    }

    /** 服务端共享 storage 构造，多个引擎实例共享同一仓库，供并行任务池化复用。 */
    FarLandsBlockLightEngine(LightChunkGetter chunkSource, FarLandsDataLayerStorage storage) {
        this.chunkSource = chunkSource;
        this.storage = storage;
        Arrays.fill(lastChunkKeys, ChunkPos.INVALID_CHUNK_POS);
    }

    // 公共 API

    public int getLightValue(long packedPos) {
        IntBlockPos bp = IntBlockPos.getBlockPos(packedPos);
        long sec = HashMath.hash(bp.x >> 4, bp.y >> 4, bp.z >> 4);
        DataLayer dl = storage.get(sec);
        return dl == null ? 0 : dl.get(bp.x & 15, bp.y & 15, bp.z & 15);
    }

    public void checkBlock(BlockPos pos) {
        synchronized (queueLock) {
            // 补注册 side-channel，checkNode 反查依赖；播种不再 putBlock 注册，
            // 客户端 runLightUpdates 路径的 levelPos 无 asLong 注册
            BlockUtil.put(HashMath.hash(pos.getX(), pos.getY(), pos.getZ()),
                    pos.getX(), pos.getY(), pos.getZ());
            blockNodesToCheck.add(HashMath.hash(pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public DataLayer getDataLayer(long sectionKey) {
        return storage.get(sectionKey);
    }

    public DataLayer getOrCreate(long sectionKey, long chunkKey) {
        return storage.getOrCreate(sectionKey, chunkKey);
    }

    public void setDataLayer(long sectionKey, DataLayer layer, long chunkKey) {
        storage.put(sectionKey, layer, chunkKey);
    }

    /** 移除某 section 的光照层，供 fsa 清理使用。 */
    public void removeDataLayer(long sectionKey, long chunkKey) {
        storage.remove(sectionKey, chunkKey);
    }

    // 服务端 per-chunk 任务入口

    /**
     * 处理一个 chunk 的方块变化 + section 变化。任务独占本引擎实例，单线程执行，
     * 队列/affectedSections 在任务内消耗。先建层处理 section 变化，再 checkNode。
     */
    public void processBlocksChanged(int chunkX, int chunkZ, Set<BlockPos> positions,
            Map<Integer, Boolean> sectionChanges) {
        taskLocal.clear(); // 批次入口
        clearSectionCache(); // 任务入口
        blockNodesToCheck.clear(); // 防池化复用残留，任务路径不经 blockNodesToCheck
        if (sectionChanges != null) {
            for (Map.Entry<Integer, Boolean> e : sectionChanges.entrySet()) {
                updateSectionStatus(SectionPos.of(chunkX, e.getKey(), chunkZ), e.getValue());
            }
        }
        for (BlockPos pos : positions) {
            // 必须用 asLong()，它会 putBlock 注册 side-channel，直接 hash 会 miss
            checkNode(pos.asLong());
        }
        runPropagation();
    }

    /**
     * 处理传播队列 + 通知。方块变化任务与播种任务共用，propagateLightSources 只
     * enqueue 光源，必须经本方法 propagate 才扩散；原同步模型靠每 tick runLightUpdates。
     */
    public void runPropagation() {
        taskLocal.clear(); // 批次入口；播种后传播
        clearSectionCache(); // 任务入口
        propagateDecreases();
        propagateIncreases();
        clearChunkCache();
        notifyLightChanges();
    }

    /** 服务端 chunk 卸载清理：按索引移除该 chunk 全部光照层，O(sections/chunk)。 */
    public void removeChunk(ChunkPos pos) {
        storage.removeChunk(ChunkPos.pack(pos.x(), pos.z()));
    }

    // runLightUpdates

    @Override
    public boolean hasLightWork() {
        return !blockNodesToCheck.isEmpty() || !decreaseQueue.isEmpty() || !increaseQueue.isEmpty();
    }

    @Override
    public int runLightUpdates() {
        synchronized (queueLock) {
            taskLocal.clear(); // 批次入口
            clearSectionCache(); // 任务入口
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

    // vanilla swapSectionMap -> onLightUpdate

    /**
     * 写入影响 block 周围所有 section，对齐 vanilla setStoredLevel 的 aroundAndAtBlockPos。
     * 传坐标，因为调用点已知 x/y/z，省 IntBlockPos.getBlockPos 反查，是传播 get 的优化。
     */
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
            chunkSource.onLightUpdate(LightLayer.BLOCK, SectionPos.of(sp.x, sp.y, sp.z));
        }
        affectedSections.clear();
    }

    // 队列循环

    private int propagateIncreases() {
        int i;
        // 队列条目 4-long 即 x,y,z,entry 坐标随条目走，传播免 side-channel 反查
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

    // 改传坐标，调用点传播队列/checkNode 处坐标已知 免 side-channel 反查。

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

    // checkNode：移植自 BlockLightEngine

    private void checkNode(long packedPos) {
        IntBlockPos bp = IntBlockPos.getBlockPos(packedPos);
        long sec = HashMath.hash(bp.x >> 4, bp.y >> 4, bp.z >> 4);
        if (!localContains(sec))
            return;

        mutablePos.set(bp.x, bp.y, bp.z);
        BlockState state = getState(bp.x, bp.y, bp.z);
        int emission = getEmission(bp.x, bp.y, bp.z, state);
        int cur = getStoredLevel(bp.x, bp.y, bp.z);

        if (emission < cur) {
            setStoredLevel(bp.x, bp.y, bp.z, 0);
            enqueueDecrease(bp.x, bp.y, bp.z, LightEngine.QueueEntry.decreaseAllDirections(cur));
        } else {
            enqueueDecrease(bp.x, bp.y, bp.z, PULL_LIGHT_IN_ENTRY);
        }
        if (emission > 0) {
            enqueueIncrease(bp.x, bp.y, bp.z,
                    LightEngine.QueueEntry.increaseLightFromEmission(emission, isEmptyShape(state)));
        }
    }

    // propagateIncrease

    private void propagateIncrease(int srcX, int srcY, int srcZ, long queueEntry, int lightLevel) {
        BlockState blockstate = null;

        for (Direction dir : PROPAGATION_DIRECTIONS) {
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir))
                continue;

            // 内联 BlockPos.offset src 坐标直接来自队列，免反查
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

            // 不再 putBlock 注册 队列存坐标，无 side-channel 反查

            if (blockstate == null) {
                blockstate = LightEngine.QueueEntry.isFromEmptyShape(queueEntry)
                        ? Blocks.AIR.defaultBlockState()
                        : getState(srcX, srcY, srcZ);
            }
            // 形状检查跳过：仅当两方块都 isEmptyShape 才跳过。
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
        }
    }

    // ==================== propagateDecrease
    private void propagateDecrease(int srcX, int srcY, int srcZ, long lightLevel) {
        int fromLevel = LightEngine.QueueEntry.getFromLevel(lightLevel);

        for (Direction dir : PROPAGATION_DIRECTIONS) {
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(lightLevel, dir))
                continue;

            // 内联 BlockPos.offset src 坐标直接来自队列，免反查
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

            // 不再 putBlock 注册 队列存坐标，无 side-channel 反查

            if (nVal <= fromLevel - 1) {
                BlockState state = getState(nbpX, nbpY, nbpZ);
                int emission = getEmission(nbpX, nbpY, nbpZ, state);
                ndl.set(nbpX & 15, nbpY & 15, nbpZ & 15, 0);
                markAffected(nbpX, nbpY, nbpZ);
                if (emission < nVal) {
                    enqueueDecrease(nbpX, nbpY, nbpZ,
                            LightEngine.QueueEntry.decreaseSkipOneDirection(nVal, dir.getOpposite()));
                }
                if (emission > 0) {
                    enqueueIncrease(nbpX, nbpY, nbpZ,
                            LightEngine.QueueEntry.increaseLightFromEmission(emission, isEmptyShape(state)));
                }
            } else {
                enqueueIncrease(nbpX, nbpY, nbpZ,
                        LightEngine.QueueEntry.increaseOnlyOneDirection(nVal, false, dir.getOpposite()));
            }
        }
    }

    // emission

    private int getEmission(int x, int y, int z, BlockState state) {
        int emission = state.getLightEmission();
        long sec = HashMath.hash(x >> 4, y >> 4, z >> 4);
        return emission > 0 && localContains(sec) ? emission : 0;
    }

    // propagateLightSources

    @Override
    public void propagateLightSources(ChunkPos pos) {
        synchronized (queueLock) {
            LightChunk lc = getChunk(pos.x(), pos.z());
            if (lc != null) {
                lc.findBlockLightSources((blockPos, state) -> {
                    int emission = state.getLightEmission();
                    // 免 asLong，即不注册 enqueue 直接带坐标
                    enqueueIncrease(blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                            LightEngine.QueueEntry.increaseLightFromEmission(emission, isEmptyShape(state)));
                });
            }
        }
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        if (isEmpty) {
            // 不删层：层保留到 chunk 卸载，由 removeChunk 清理。立即删层会让
            // checkNode 的 localContains 早退 → decrease 不传播 -> 相邻 section
            // 光残留，打掉方块后 section 变空场景下，跨任务时序无法保证 checkNode 先于删层。
        } else {
            storage.getOrCreate(pos.asLong(), chunkKey);
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
        // 无操作 方块光没有列启用/禁用
    }

    // chunk 缓存

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

    // 方块访问

    /** 任务入口清 section 缓存，包括 2-slot 与任务内缓存，防跨任务读旧 section。 */
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
        this.lastKx = Integer.MIN_VALUE; // secKey 缓存哨兵重置
    }

    // 坐标版 getState + 2-slot section 缓存 传播调用点坐标已知，省 mutablePos + 装箱。
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
            // 先查任务内 section 缓存，fastutil 无装箱 CHM.get 只剩任务内首访
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
            // 列掩码快路径 该行无方块即空气 -> 直接 AIR 常量，免 palette 查找。
            // columnMasksIfReady 不 rebuild：懒创建空 section 掩码 null → 走 palette。
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

    private static boolean isEmptyShape(BlockState state) {
        return !state.canOcclude() || !state.useShapeForLightOcclusion();
    }

    private boolean shapeOccludes(BlockState state1, BlockState state2, Direction dir) {
        VoxelShape shape1 = LightEngine.getOcclusionShape(state1, dir);
        VoxelShape shape2 = LightEngine.getOcclusionShape(state2, dir.getOpposite());
        return Shapes.faceShapeOccludes(shape1, shape2);
    }
}
