package com.inf.farlands;

import java.util.Map;

import com.inf.farlands.util.config.Config;
import com.inf.farlands.util.config.ConfigEntry;

public class FarlandsConfig {
        public static final ConfigEntry<Integer> BORDER_ABSOLUTE_MAX = Config.register(
                        "borderAbsoluteMax",
                        int.class,
                        FarlandsConstant.MAX_BLOCK,
                        Map.of("en_us", "Maximum world border size", "zh_cn", "世界边界最大尺寸"));
        public static final int borderAbsoluteMax;

        public static final ConfigEntry<Integer> WORLD_GEN_MIN_Y = Config.register(
                        "worldGenMinY",
                        int.class,
                        -FarlandsConstant.MAX_BLOCK,
                        Map.of("en_us", "Minimum absolute Y for world generation", "zh_cn", "世界生成的绝对最小 Y"));
        public static final int worldGenMinY;

        public static final ConfigEntry<Integer> WORLD_GEN_MAX_Y = Config.register(
                        "worldGenMaxY",
                        int.class,
                        FarlandsConstant.MAX_BLOCK,
                        Map.of("en_us", "Maximum absolute Y for world generation", "zh_cn", "世界生成的绝对最大 Y"));
        public static final int worldGenMaxY;

        // 通用循环上限

        public static final ConfigEntry<Integer> MAX_CAP_ITER = Config.register(
                        "maxCapIter",
                        int.class,
                        512,
                        Map.of("en_us", "Iteration/search cap for extreme-Y loops", "zh_cn", "极端 Y 循环的迭代/搜索上限"));
        public static final int maxCapIter;

        public static final ConfigEntry<Integer> VERTICAL_SIMULATION_DISTANCE = Config.register(
                        "verticalSimulationDistance",
                        int.class,
                        17,
                        Map.of("en_us", "Radius of the vertical simulation distance.", "zh_cn", "垂直模拟距离的半径"));
        public static final int verticalSimulationDistance;

        public static final ConfigEntry<Integer> FSA_CLEANUP_MARGIN = Config.register(
                        "fsaCleanupMargin",
                        int.class,
                        8,
                        Map.of("en_us", "Sections outside all player windows plus this margin are cleanup candidates.",
                                        "zh_cn", "玩家窗口并集外加上该余量的 section 为清理候选。"));
        public static final int fsaCleanupMargin;

        // 光照

        public static final ConfigEntry<Integer> PARALLEL_LIGHT_THREADS = Config.register(
                        "parallelLightThreads",
                        int.class,
                        0,
                        Map.of("en_us",
                                        "Server-side light propagation threads. 0 = auto: half of CPU logical processors; 1 = single background thread; N = exactly N threads (max 64).",
                                        "zh_cn",
                                        "服务端光照传播线程数。0 = 自动：CPU 逻辑线程数一半；1 = 单个后台线程；N = 恰好 N 个线程（上限 64）。"));
        public static final int parallelLightThreads;

        public static final ConfigEntry<Integer> MAX_LIGHT_TASKS_PER_TICK = Config.register(
                        "maxLightTasksPerTick",
                        int.class,
                        4,
                        Map.of("en_us",
                                        "Max light propagation tasks submitted per wake (each server tick wakes the drain loop once).",
                                        "zh_cn",
                                        "每次唤醒提交的光照传播任务上限（服务端每 tick 唤醒一次 drain 循环）。"));
        public static final int maxLightTasksPerTick;

        public static final ConfigEntry<Integer> SECTION_SEND_BYTES_PER_TICK = Config.register(
                        "sectionSendBytesPerTick",
                        int.class,
                        131072,
                        Map.of("en_us",
                                        "Max window-slide §5 section bytes sent per tick per player.",
                                        "zh_cn",
                                        "每 tick 每玩家发送的 §5 窗口滑动 section 最大字节数。"));
        public static final int sectionSendBytesPerTick;

        static {
                Config.init();
                borderAbsoluteMax = BORDER_ABSOLUTE_MAX.get();
                worldGenMinY = WORLD_GEN_MIN_Y.get();
                worldGenMaxY = WORLD_GEN_MAX_Y.get();
                maxCapIter = MAX_CAP_ITER.get();
                verticalSimulationDistance = VERTICAL_SIMULATION_DISTANCE.get();
                fsaCleanupMargin = FSA_CLEANUP_MARGIN.get();
                parallelLightThreads = resolveLightThreads(PARALLEL_LIGHT_THREADS.get());
                maxLightTasksPerTick = MAX_LIGHT_TASKS_PER_TICK.get();
                sectionSendBytesPerTick = SECTION_SEND_BYTES_PER_TICK.get();
        }

        private static int resolveLightThreads(int raw) {
                if (raw <= 0) {
                        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                }
                return Math.min(64, raw);
        }
}
