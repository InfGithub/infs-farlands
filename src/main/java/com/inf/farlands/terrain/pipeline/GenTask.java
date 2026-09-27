package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * chunk 级生成任务，状态转换批。一个任务对应一个 chunk，execute 时收集玩家窗口并集内该
 * chunk 未生成的 section，按连续段分组，每段一个 NoiseChunk 填充，固定成本从每 section 一次
 * 降到每段一次。
 *
 * 瞬态：触发即建，执行即毁。持 LevelChunk 强引用以防 GC。fill 在途由 GenQueue 的 ticket
 * 保加载，chunk 不卸载，光照播种对象始终正确。
 *
 * 并发：同 chunk 至多一个任务在途，GenQueue 的 CHUNK_IN_FLIGHT CAS 去重，因此 fill 无并发写
 * 高度图。不同 chunk 并行。
 */
public final class GenTask {

    private final LevelChunk chunk;
    /** 预加载模式：指定 section 集合。null 表示窗口模式，用玩家窗口并集。 */
    private final int[] preloadSections;
    /** 该 chunk 到最近玩家的 XZ Chebyshev 距离，入队时快照，玩家移动时 refreshPriority 重算。 */
    private int priority;

    public GenTask(LevelChunk chunk) {
        this(chunk, null);
    }

    public GenTask(LevelChunk chunk, int[] preloadSections) {
        this.chunk = chunk;
        this.preloadSections = preloadSections;
        this.priority = computePriority(chunk);
    }

    int priority() {
        return this.priority;
    }

    /** 玩家位置变化时由 GenQueue.rebuildQueue 调用重算，修复快照旧。 */
    void refreshPriority() {
        this.priority = computePriority(this.chunk);
    }

    /** 距最近玩家距离。无玩家时返回 0，退化为 FIFO。 */
    private static int computePriority(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel sl) {
            ChunkPos cp = chunk.getPos();
            int best = Integer.MAX_VALUE;
            for (ServerPlayer p : sl.players()) {
                ChunkPos pp = p.chunkPosition();
                int d = Math.max(Math.abs(cp.x() - pp.x()), Math.abs(cp.z() - pp.z()));
                if (d < best) {
                    best = d;
                }
            }
            return best == Integer.MAX_VALUE ? 0 : best;
        }
        return 0;
    }

    public void execute() {
        try {
            Level level = chunk.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            List<int[]> segments = collectSegments();
            for (int[] seg : segments) {
                try {
                    GenQueue.filler(serverLevel).fill(serverLevel, chunk, seg[0], seg[1]);
                } catch (Exception e) {
                    InfsFarlands.LOGGER.error("GENTASK fill ex chunk={},{} {}",
                            chunk.getPos().x(), chunk.getPos().z(), e.toString());
                    throw e;
                }
                for (int sy = seg[0]; sy <= seg[1]; sy++) {
                    SectionStage.setStage(chunk, sy, SectionStage.TERRAIN);
                    // fsa 脏标记：fill 在 genPool 线程写 section 内容，CHM 安全
                    ((WindowedChunk) chunk).markSectionDirty(sy);
                }
            }
            // SURFACE 独立于 segments，fill 后紧跟，因为依赖 fill 产出的高度图，也覆盖读回
            // stage 为 TERRAIN 与 surface 失败残留。失败不抛，section 停留 TERRAIN 由 scanAndEnqueue
            // 补触发重试。不触发光照，promoteAllGenToLighted 会把 TERRAIN 升 LIGHTED，抹掉待处理标志。
            try {
                SurfaceFiller.applySurfaceIfNeeded(serverLevel, chunk);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("GENTASK surface ex chunk={},{} {}",
                        chunk.getPos().x(), chunk.getPos().z(), e.toString());
            }
            // CARVERS 独立于 segments 与 surface，surface 后紧跟，只替换 fill 产物方块。
            // 失败处理同 surface。
            int[] carved = new int[0];
            try {
                carved = CarverFiller.applyCarversIfNeeded(serverLevel, chunk);
            } catch (Exception e) {
                InfsFarlands.LOGGER.error("GENTASK carvers ex chunk={},{} {}",
                        chunk.getPos().x(), chunk.getPos().z(), e.toString());
            }
            // 光照触发条件：carvers 产出了东西，或读回后停在 TERRAIN 到 CARVERS 之间的段。
            // 后者是 fsa 恢复路径的稳定态，三条 pending 判据都不认它，carvers 因此为空，
            // 只按 carvers 判会永远等不到光照。
            int[] belowLighted = belowLightedSections();
            if (carved.length > 0 || belowLighted.length > 0) {
                // 恢复的段不脏，applyDecoded 只恢复 stage。不标脏则 persistChunkDirty 写不到它，
                // stage 会一直停在盘上的旧值，每次重进都要重算一遍光照。
                for (int sy : belowLighted) {
                    ((WindowedChunk) chunk).markSectionDirty(sy);
                }
                GenQueue.notifyGenerated(chunk);
                // fill、surface、carvers 直接写 section，没有 vanilla 广播；这里只标记该 chunk
                // 内容已变，由 ChunkDataSender 每 tick 按玩家当前窗口物化后再发 §5 包。
                // 放在 notifyGenerated 之后，flush 时 LIGHT_IN_FLIGHT 必为真，于是留队列等光照
                // 完成，方块与光照同到。
                ChunkDataSender.markChunkChanged(chunk);
            }
        } finally {
            // fill 异常也清理，清在途并释放 ticket。异常路径若不清理会让标志残留，chunk 永不卸载。
            GenQueue.completeTask(chunk);
        }
    }

    /**
     * 做过但没点亮的 section，即 stage 落在 TERRAIN 到 CARVERS 之间的段，光照补触发的判据。
     *
     * <p>它是 fsa 恢复路径的稳定态：collectSegments 跳过 isOrAfter 为 TERRAIN 的段，SurfaceFiller
     * 只认 TERRAIN 未 SURFACE 的段，CarverFiller 只认 SURFACE 未 CARVERS 的段，三条 pending 判据
     * 都不认 stage 已是 CARVERS 的段，carvers 因此为空，光照永远不会被触发。UNPROCESSED 与 BIOMES
     * 不在判据内：窗口内的由生成收口，窗口外的永不处理。
     *
     * <p>按 stage 载体取，不按 allSections 取：非噪声生成器的维度里 fill 会早退、段没被建出来，
     * 而 GenTask 已经推进了 stage，按 allSections 会漏掉它们。
     */
    private int[] belowLightedSections() {
        List<Integer> out = new ArrayList<>();
        SectionStage.forEachStage(chunk, (sy, stage) -> {
            if (stage >= SectionStage.TERRAIN && stage < SectionStage.LIGHTED) {
                out.add(sy);
            }
        });
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * 收集该 chunk 的待生成 section 连续段，来源是窗口并集或预加载指定集合。
     * 过滤已 TERRAIN 的 section 与可玩范围，clamp 段顶防溢出。
     * 窗口模式额外跳过读回在途的 section，读回完成回调会入队。
     */
    private List<int[]> collectSegments() {
        List<int[]> segments = new ArrayList<>();
        int[] curMin = { 0 };
        int[] curMax = { -1 };
        boolean[] open = { false };
        IntConsumer consider = sy -> {
            if (!WorldBounds.inSection(sy)) {
                return;
            }
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.TERRAIN)) {
                return;
            }
            if (preloadSections == null && SectionIO.isReading(chunk.getPos().pack(), sy)) {
                return;
            }
            if (open[0] && sy == curMax[0] + 1) {
                curMax[0] = sy;
            } else {
                if (open[0]) {
                    segments.add(new int[] { curMin[0], curMax[0] });
                }
                curMin[0] = sy;
                curMax[0] = sy;
                open[0] = true;
            }
        };
        if (preloadSections != null) {
            for (int sy : preloadSections) {
                consider.accept(sy);
            }
        } else {
            EntitySectionWindow.forEachSectionInAnyWindow(consider);
        }
        if (open[0]) {
            segments.add(new int[] { curMin[0], curMax[0] });
        }
        return segments;
    }
}
