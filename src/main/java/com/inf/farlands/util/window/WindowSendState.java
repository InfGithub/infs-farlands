package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.terrain.pipeline.GenQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 服务端发包线程的 per-player 窗口状态，存于 ThreadLocal。
 *
 * PlayerChunkSenderMixin 在 PlayerChunkSender.sendChunk 前后设置与清除，包体构造据此决定发送
 * 范围。发送是服务端主线程串行的，ThreadLocal 设与清成对；sendChunk 循环内每个 chunk 重新设置
 * 同玩家时值相同，无害。异常路径的残留会被下一次 HEAD 覆盖。
 */
public final class WindowSendState {
    private WindowSendState() {
    }

    private static final ThreadLocal<Integer> WINDOW_MIN_Y = new ThreadLocal<>();

    public static void setWindowMinY(int minY) {
        WINDOW_MIN_Y.set(minY);
    }

    public static void clear() {
        WINDOW_MIN_Y.remove();
    }

    /** 发送范围取 ThreadLocal 窗口，即发送时刻的玩家窗口；无则回退 chunk 自身的窗口下界。 */
    private static int windowMinY(LevelChunk chunk) {
        Integer v = WINDOW_MIN_Y.get();
        return v != null ? v : ((WindowedChunk) chunk).getWindowMinY();
    }

    /**
     * 窗口内非空 section 列表，按绝对 sectionY 升序。calculateChunkSize 与 extractChunkData 必须
     * 共用同一过滤，否则 buffer 尺寸与实际写入不匹配，26.1.2 会在写满断言上抛异常。
     *
     * fill 或光照在途即 GenQueue.isChunkBusy 的 chunk 返回空列表：genPool 并发写 section 时，
     * 两次遍历之间的 section 集合与内容会不一致；过滤后 section 稳定才打包。空出来的数据由
     * fill 完成后的 §5 section 包补齐。
     */
    public static List<Map.Entry<Integer, LevelChunkSection>> sendableSections(LevelChunk chunk) {
        if (GenQueue.isChunkBusy(chunk)) {
            return List.of();
        }
        int minY = windowMinY(chunk);
        int maxY = minY + FarlandsConfig.verticalSimulationDistance * 2;
        List<Map.Entry<Integer, LevelChunkSection>> out = new ArrayList<>();
        for (Map.Entry<Integer, LevelChunkSection> e : ((WindowedChunk) chunk).windowedAllSections().entrySet()) {
            int sy = e.getKey();
            LevelChunkSection s = e.getValue();
            if (sy >= minY && sy <= maxY && s != null && !s.hasOnlyAir()) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(Map.Entry::getKey));
        return out;
    }
}
