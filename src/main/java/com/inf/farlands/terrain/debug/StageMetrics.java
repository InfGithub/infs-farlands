package com.inf.farlands.terrain.debug;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.InfsFarlands;

/**
 * 就绪吞吐读数：每窗口各一次，报 chunk 与 section 的完成速率。
 *
 * <p>两个 cum 率的分子是「会话累计」，分母必须是「会话秒数」，不能是窗口秒数：拿累计数除窗口秒数会让
 * cum 率每窗口被放大一次，读数随会话单调虚高，而窗口那两个率才是当下速率。
 *
 * <p>事件口径：一个 chunk 在它的 lightChunk 回调成功、段被升到 LIGHTED 那一刻计一次；section 按
 * 同一次回调里升级的段数累加。所以 cps 衡量的是整条生成链走完的速率，不是某一阶段的速率。
 */
public final class StageMetrics {

    /**
     * 打进日志的窗口 tick 数，只作标记。
     *
     * <p>它不是触发间隔：调用方按自己的周期决定何时调 {@link #flush()}，本类不检查 tick 计数。所以这个值
     * 必须与调用方那个周期一致，否则日志里的窗口长度会与实际不符。
     */
    public static final int WINDOW_TICKS = 200;

    private static final AtomicLong SECTIONS_IN = new AtomicLong();
    private static final AtomicLong SECTIONS_LIGHTED = new AtomicLong();
    private static final AtomicLong CHUNKS_LIGHTED = new AtomicLong();

    /** 上次 flush 时刻，只用于算窗口秒数。 */
    private static long anchorNanos = System.nanoTime();
    private static long anchorSectionsLighted;
    private static long anchorChunksLighted;
    /** 累计秒数。累计率的分母必须是它，不能是窗口秒数。 */
    private static double cumSeconds;

    private StageMetrics() {
    }

    /** 一个 chunk 完成点亮。 */
    public static void lightedChunk() {
        CHUNKS_LIGHTED.incrementAndGet();
    }

    /** 一次点亮回调里升级的段数。 */
    public static void lightedSections(int upgraded) {
        if (upgraded > 0) {
            SECTIONS_LIGHTED.addAndGet(upgraded);
        }
    }

    /** 进入 BIOMES 的段数，即进入管线的段数。 */
    public static void sectionsIn(long n) {
        if (n > 0L) {
            SECTIONS_IN.addAndGet(n);
        }
    }

    /**
     * 每 {@link #WINDOW_TICKS} tick 一次，主线程调用。先打印本窗口，再把窗口锚点推到现在。
     *
     * <p>只报读数，不报解读。
     */
    public static void flush() {
        long now = System.nanoTime();
        double windowSeconds = Math.max(1.0E-3, (now - anchorNanos) / 1.0E9);
        cumSeconds += windowSeconds;
        long sectionsIn = SECTIONS_IN.get();
        long sectionsLighted = SECTIONS_LIGHTED.get();
        long chunksLighted = CHUNKS_LIGHTED.get();

        if (FarlandsConfig.logStageMetrics) {
            InfsFarlands.LOGGER.info(
                    "FLSTAGE window={}t elapsed={}s cps={} sps={} cum_cps={} cum_sps={} sectIn={}",
                    WINDOW_TICKS, fmt(windowSeconds),
                    fmt(chunksLighted / windowSeconds), fmt(sectionsLighted / windowSeconds),
                    fmt((anchorChunksLighted + chunksLighted) / cumSeconds),
                    fmt((anchorSectionsLighted + sectionsLighted) / cumSeconds),
                    sectionsIn);
        }

        anchorNanos = now;
        anchorSectionsLighted += sectionsLighted;
        anchorChunksLighted += chunksLighted;
        SECTIONS_IN.set(0L);
        SECTIONS_LIGHTED.set(0L);
        CHUNKS_LIGHTED.set(0L);
    }

    /** 停服时清空计数与累计锚点。 */
    public static void clearWorldState() {
        SECTIONS_IN.set(0L);
        SECTIONS_LIGHTED.set(0L);
        CHUNKS_LIGHTED.set(0L);
        anchorSectionsLighted = 0L;
        anchorChunksLighted = 0L;
        cumSeconds = 0.0;
        anchorNanos = System.nanoTime();
    }

    /** 两位小数。 */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
