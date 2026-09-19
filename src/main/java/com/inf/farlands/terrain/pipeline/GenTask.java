package com.inf.farlands.terrain.pipeline;

import com.inf.farlands.FarlandsConstant;
import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.carverFiller.CarverFiller;
import com.inf.farlands.terrain.surfaceFiller.SurfaceFiller;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;

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
                    SectionStage.setStage(chunk, sy, SectionStage.NOISE);
                    // fsa 脏标记：fill 在 genPool 线程写 section 内容，CHM 安全
                    ((WindowedChunk) chunk).markSectionDirty(sy);
                }
            }
            // SURFACE 独立于 segments，fill 后紧跟，因为依赖 fill 产出的高度图，也覆盖读回
            // stage 为 NOISE 与 surface 失败残留。失败不抛，section 停留 NOISE 由 scanAndEnqueue
            // 补触发重试。不触发光照，promoteAllGenToLighted 会把 NOISE 升 LIGHTED，抹掉待处理标志。
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
            // 光照触发条件是 carvers 完成，carvers 是光照前最后一个阶段。
            if (carved.length > 0) {
                GenQueue.notifyGenerated(chunk);
                for (int sy : carved) {
                    // fill、surface、carvers 直接写 section，没有 vanilla 广播，这里补入发送队列，
                    // 下 tick flush 发 section 包。放在 notifyGenerated 之后，flush 时检查
                    // LIGHT_IN_FLIGHT 必为真，于是留队列等光照完成，方块与光照同到。
                    ChunkDataSender.enqueueSectionSend(chunk, sy);
                }
            }
        } finally {
            // fill 异常也清理，清在途并释放 ticket。异常路径若不清理会让标志残留，chunk 永不卸载。
            GenQueue.completeTask(chunk);
        }
    }

    /**
     * 收集该 chunk 的待生成 section 连续段，来源是窗口并集或预加载指定集合。
     * 过滤已 NOISE 的 section 与可玩范围，clamp 段顶防溢出。
     * 窗口模式额外跳过读回在途的 section，读回完成回调会入队。
     */
    private List<int[]> collectSegments() {
        List<int[]> segments = new ArrayList<>();
        int[] curMin = { 0 };
        int[] curMax = { -1 };
        boolean[] open = { false };
        IntConsumer consider = sy -> {
            if (sy > FarlandsConstant.MAX_CHUNK - 1 || sy < -FarlandsConstant.MAX_CHUNK) {
                return;
            }
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.NOISE)) {
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
