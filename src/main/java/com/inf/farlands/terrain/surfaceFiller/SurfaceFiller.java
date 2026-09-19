package com.inf.farlands.terrain.surfaceFiller;

import com.inf.farlands.FarlandsConstant;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.system.SurfaceSystemRegistry;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * SURFACE 阶段编排：何时跑、状态推进到 SURFACE、按维度分派 SurfaceSystem。
 *
 * surface 依赖 fill 产出的高度图，必须紧跟 fill 且同 chunk 串行，由 GenTask 内保证。
 *
 * 触发：GenTask fill 完成后调 applySurfaceIfNeeded，新 fill 的 section 必然是 NOISE 未
 * SURFACE。补触发：读回 stage 为 NOISE 的 section 由 GenQueue.scanAndEnqueue 的
 * hasSurfacePending 检查入队重试。
 *
 * 失败策略：applySurface 异常由 GenTask 捕获，section 停留 NOISE，scanAndEnqueue 补触发重试。
 */
public final class SurfaceFiller {

    private SurfaceFiller() {
    }

    /**
     * 该 chunk 是否已有 NOISE 未 SURFACE 且非读回的 section。
     * 全 chunk 检测，非窗口并集，预加载场景窗口为空时也能触发。
     */
    public static boolean hasSurfacePending(LevelChunk chunk) {
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (sy > FarlandsConstant.MAX_CHUNK - 1 || sy < -FarlandsConstant.MAX_CHUNK) {
                continue;
            }
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.NOISE)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)
                    && !SectionIO.isReading(chunk.getPos().pack(), sy)) {
                return true;
            }
        }
        return false;
    }

    /** 编排：有 surface 待处理才跑，维度系统应用加升 SURFACE 加标脏。返回本次升段的 sectionY。 */
    public static int[] applySurfaceIfNeeded(ServerLevel level, LevelChunk chunk) {
        if (!hasSurfacePending(chunk)) {
            return new int[0];
        }
        systemFor(level).applySurface(level, chunk);
        // surface 修改方块，读回的 section 未标脏，不标就会写盘丢 surface 结果。
        // fill 已标脏的重复标无害，幂等。
        List<Integer> list = new ArrayList<>();
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            if (SectionStage.isOrAfter(chunk, sy, SectionStage.NOISE)
                    && !SectionStage.isOrAfter(chunk, sy, SectionStage.SURFACE)) {
                list.add(sy);
            }
        }
        int[] surfaced = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            int sy = list.get(i);
            surfaced[i] = sy;
            SectionStage.setStage(chunk, sy, SectionStage.SURFACE);
            ((WindowedChunk) chunk).markSectionDirty(sy);
        }
        return surfaced;
    }

    /** 按维度 id 分派。Level.NETHER 与 Level.END 是 ResourceKey，不是枚举，不能用 switch。 */
    private static SurfaceSystem systemFor(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            return SurfaceSystemRegistry.getTheNether();
        }
        if (level.dimension() == Level.END) {
            return SurfaceSystemRegistry.getTheEnd();
        }
        return SurfaceSystemRegistry.getOverworld();
    }
}
