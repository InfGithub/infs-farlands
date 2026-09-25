package com.inf.farlands;

import com.inf.farlands.util.config.Config;
import com.inf.farlands.util.config.ConfigEntry;

/**
 * 本 mod 的全部配置项。逐项 builder 声明，见 {@link Config}。
 *
 * <p>
 * 值域一律由 {@code .range(...)} 声明，越界在读盘期抛异常——不再有"就地钳制"：
 * 钳制会留下"磁盘值越界、内存值被改过"的两份状态，越界即抛则只有一份。
 *
 * <p>
 * 两个线程数项的值可以写 {@code "auto"}，由 {@link #autoThreads()} 在声明处提供取值器。
 */
public class FarlandsConfig {

        /** {@code "auto"} 的取值器：CPU 逻辑线程数一半，最小 1。 */
        private static int autoThreads() {
                return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }

        public static final ConfigEntry<Integer> BORDER_ABSOLUTE_MAX = Config.setInt("borderAbsoluteMax")
                        .comment("en_us", "Maximum world border size")
                        .comment("zh_cn", "世界边界最大尺寸")
                        .min(1)
                        .define(FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int borderAbsoluteMax;

        public static final ConfigEntry<Boolean> OUTSIDE = Config.setBoolean("outside")
                        .comment("en_us", "Allow entities to leave the world, disabling the six-sided boundary")
                        .comment("zh_cn", "允许实体离开世界，关闭六面边界")
                        .define(false)
                        .build();
        public static final boolean outside;

        public static final ConfigEntry<Integer> WORLD_GEN_MIN_Y = Config.setInt("worldGenMinY")
                        .comment("en_us", "Minimum absolute Y for world generation")
                        .comment("zh_cn", "世界生成的绝对最小 Y")
                        .define(~FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int worldGenMinY;

        public static final ConfigEntry<Integer> WORLD_GEN_MAX_Y = Config.setInt("worldGenMaxY")
                        .comment("en_us", "Maximum absolute Y for world generation")
                        .comment("zh_cn", "世界生成的绝对最大 Y")
                        .define(FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int worldGenMaxY;

        // 通用循环上限

        public static final ConfigEntry<Integer> MAX_CAP_ITER = Config.setInt("maxCapIter")
                        .comment("en_us", "Iteration cap for Y-axis loops")
                        .comment("zh_cn", "Y 轴循环的迭代上限")
                        .range(1, 65536)
                        .define(512)
                        .build();
        public static final int maxCapIter;

        public static final ConfigEntry<Integer> VERTICAL_SIMULATION_DISTANCE = Config
                        .setInt("verticalSimulationDistance")
                        .comment("en_us", "Radius of the vertical simulation distance")
                        .comment("zh_cn", "垂直模拟距离的半径")
                        .range(1, 64)
                        .define(17)
                        .build();
        public static final int verticalSimulationDistance;

        public static final ConfigEntry<Integer> FSA_CLEANUP_MARGIN = Config.setInt("fsaCleanupMargin")
                        .comment("en_us",
                                        "Sections outside all player windows plus this margin are cleanup candidates")
                        .comment("zh_cn", "玩家窗口并集外加上该余量的 section 为清理候选")
                        .range(0, 64)
                        .define(8)
                        .build();
        public static final int fsaCleanupMargin;

        public static final ConfigEntry<Integer> FSA_CACHE_LIMIT = Config.setInt("fsaCacheLimit")
                        .comment("en_us", "Maximum number of open fsa files kept in cache")
                        .comment("zh_cn", "打开的 fsa 文件缓存上限")
                        .range(16, 1024)
                        .define(128)
                        .build();
        public static final int fsaCacheLimit;

        public static final ConfigEntry<Long> FSA_PERSIST_INTERVAL = Config.setLong("fsaPersistInterval")
                        .comment("en_us", "Interval in ticks between periodic persistence of dirty sections")
                        .comment("zh_cn", "定期持久化脏 section 的间隔，单位 tick")
                        .range(100L, 120000L)
                        .define(6000L)
                        .build();
        public static final long fsaPersistInterval;

        // 地形管线

        public static final ConfigEntry<Integer> MAX_GEN_TASKS_PER_TICK = Config.setInt("maxGenTasksPerTick")
                        .comment("en_us", "Max terrain-pipeline gen tasks consumed per wake")
                        .comment("zh_cn", "地形管线每次唤醒消费的生成任务上限")
                        .range(1, 65536)
                        .define(2000)
                        .build();
        public static final int maxGenTasksPerTick;

        public static final ConfigEntry<Integer> GEN_WORKER_THREADS = Config.setInt("genWorkerThreads")
                        .comment("en_us",
                                        "Terrain generation worker threads")
                        .comment("zh_cn",
                                        "地形生成线程数")
                        .range(1, 64)
                        .keyword("auto", FarlandsConfig::autoThreads)
                        .commentKeyword("auto", "en_us", "Half of the CPU logical processors, min 1")
                        .commentKeyword("auto", "zh_cn", "CPU 逻辑线程数的一半，最小值为 1")
                        .define("auto")
                        .build();
        public static final int genWorkerThreads;

        // 光照

        public static final ConfigEntry<Integer> PARALLEL_LIGHT_THREADS = Config.setInt("parallelLightThreads")
                        .comment("en_us",
                                        "Server-side light propagation threads")
                        .comment("zh_cn",
                                        "服务端光照传播线程数")
                        .range(1, 64)
                        .keyword("auto", FarlandsConfig::autoThreads)
                        .commentKeyword("auto", "en_us", "Half of the CPU logical processors, min 1")
                        .commentKeyword("auto", "zh_cn", "CPU 逻辑线程数的一半，最小值为 1")
                        .define("auto")
                        .build();
        public static final int parallelLightThreads;

        public static final ConfigEntry<Integer> MAX_LIGHT_TASKS_PER_TICK = Config.setInt("maxLightTasksPerTick")
                        .comment("en_us",
                                        "Max light propagation tasks submitted per wake")
                        .comment("zh_cn",
                                        "每次唤醒提交的光照传播任务上限")
                        .range(1, 64)
                        .define(4)
                        .build();
        public static final int maxLightTasksPerTick;

        public static final ConfigEntry<Integer> SECTION_SEND_BYTES_PER_TICK = Config.setInt("sectionSendBytesPerTick")
                        .comment("en_us",
                                        "Max window-slide section bytes sent per tick per player")
                        .comment("zh_cn",
                                        "每 tick 每玩家发送的窗口滑动 section 最大字节数")
                        .range(1024, 1048576)
                        .define(131072)
                        .build();
        public static final int sectionSendBytesPerTick;

        static {
                // 唯一读盘点：读一次、解析一次、约束一次。本类初始化由首个引用它的类触发。
                Config.init();
                borderAbsoluteMax = BORDER_ABSOLUTE_MAX.get();
                outside = OUTSIDE.get();
                worldGenMinY = WORLD_GEN_MIN_Y.get();
                worldGenMaxY = WORLD_GEN_MAX_Y.get();
                maxCapIter = MAX_CAP_ITER.get();
                verticalSimulationDistance = VERTICAL_SIMULATION_DISTANCE.get();
                fsaCleanupMargin = FSA_CLEANUP_MARGIN.get();
                fsaCacheLimit = FSA_CACHE_LIMIT.get();
                fsaPersistInterval = FSA_PERSIST_INTERVAL.get();
                maxGenTasksPerTick = MAX_GEN_TASKS_PER_TICK.get();
                genWorkerThreads = GEN_WORKER_THREADS.get();
                parallelLightThreads = PARALLEL_LIGHT_THREADS.get();
                maxLightTasksPerTick = MAX_LIGHT_TASKS_PER_TICK.get();
                sectionSendBytesPerTick = SECTION_SEND_BYTES_PER_TICK.get();
        }
}
