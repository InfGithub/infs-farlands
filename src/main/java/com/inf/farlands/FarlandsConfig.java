package com.inf.farlands;

import com.inf.farlands.terrain.system.biome.BiomeSystemType;
import com.inf.farlands.terrain.system.carver.CarverSystemType;
import com.inf.farlands.terrain.system.surface.SurfaceSystemType;
import com.inf.farlands.terrain.system.terrain.TerrainSystemType;
import com.inf.farlands.util.config.Config;
import com.inf.farlands.util.config.ConfigEntry;

/**
 * 本 mod 的全部配置项。逐项 builder 声明，见 {@link Config}。
 *
 * <p>值域一律由 {@code .range(...)} 声明，越界在读盘期抛异常——不再有"就地钳制"：
 * 钳制会留下"磁盘值越界、内存值被改过"的两份状态，越界即抛则只有一份。
 *
 * <p>两个线程数项的值可以写 {@code "auto"}，由 {@link #autoThreads()} 在声明处提供取值器。
 */
public class FarlandsConfig {

        /** {@code "auto"} 的取值器：CPU 逻辑线程数一半，最小 1。 */
        private static int autoThreads() {
                return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }

        public static final ConfigEntry<Integer> BORDER_ABSOLUTE_MAX = Config.setInt("borderAbsoluteMax")
                        .comment("en_us", "Maximum world border size")
                        .comment("zh_cn", "世界边界最大尺寸")
                        .range(1, FarlandsConstant.MAX_BLOCK)
                        .Default(FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int borderAbsoluteMax;

        public static final ConfigEntry<Integer> WORLD_GEN_MIN_Y = Config.setInt("worldGenMinY")
                        .comment("en_us", "Minimum absolute Y for world generation")
                        .comment("zh_cn", "世界生成的绝对最小 Y")
                        .range(-FarlandsConstant.MAX_BLOCK, FarlandsConstant.MAX_BLOCK)
                        .Default(-FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int worldGenMinY;

        public static final ConfigEntry<Integer> WORLD_GEN_MAX_Y = Config.setInt("worldGenMaxY")
                        .comment("en_us", "Maximum absolute Y for world generation")
                        .comment("zh_cn", "世界生成的绝对最大 Y")
                        .range(-FarlandsConstant.MAX_BLOCK, FarlandsConstant.MAX_BLOCK)
                        .Default(FarlandsConstant.MAX_BLOCK)
                        .build();
        public static final int worldGenMaxY;

        // 通用循环上限

        public static final ConfigEntry<Integer> MAX_CAP_ITER = Config.setInt("maxCapIter")
                        .comment("en_us", "Iteration/search cap for extreme-Y loops")
                        .comment("zh_cn", "极端 Y 循环的迭代/搜索上限")
                        .range(1, 65536)
                        .Default(512)
                        .build();
        public static final int maxCapIter;

        public static final ConfigEntry<Integer> VERTICAL_SIMULATION_DISTANCE = Config.setInt("verticalSimulationDistance")
                        .comment("en_us", "Radius of the vertical simulation distance.")
                        .comment("zh_cn", "垂直模拟距离的半径")
                        .range(1, 64)
                        .Default(17)
                        .build();
        public static final int verticalSimulationDistance;

        public static final ConfigEntry<Integer> FSA_CLEANUP_MARGIN = Config.setInt("fsaCleanupMargin")
                        .comment("en_us", "Sections outside all player windows plus this margin are cleanup candidates.")
                        .comment("zh_cn", "玩家窗口并集外加上该余量的 section 为清理候选。")
                        .range(0, 64)
                        .Default(8)
                        .build();
        public static final int fsaCleanupMargin;

        public static final ConfigEntry<Integer> FSA_CACHE_LIMIT = Config.setInt("fsaCacheLimit")
                        .comment("en_us", "Maximum number of open fsa files kept in cache.")
                        .comment("zh_cn", "打开的 fsa 文件缓存上限。")
                        .range(16, 1024)
                        .Default(128)
                        .build();
        public static final int fsaCacheLimit;

        public static final ConfigEntry<Long> FSA_PERSIST_INTERVAL = Config.setLong("fsaPersistInterval")
                        .comment("en_us", "Interval in ticks between periodic persistence of dirty sections.")
                        .comment("zh_cn", "定期持久化脏 section 的间隔，单位 tick。")
                        .range(100L, 120000L)
                        .Default(6000L)
                        .build();
        public static final long fsaPersistInterval;

        // 地形系统

        public static final ConfigEntry<TerrainSystemType> OVERWORLD_TERRAIN_SYSTEM = Config
                        .setEnum("overworldTerrainSystem", TerrainSystemType.class)
                        .comment("en_us", "Terrain system for overworld generation.")
                        .comment("zh_cn", "主世界地形系统。")
                        .Default(TerrainSystemType.VOID)
                        .build();
        public static final TerrainSystemType overworldTerrainSystem;

        public static final ConfigEntry<TerrainSystemType> NETHER_TERRAIN_SYSTEM = Config
                        .setEnum("theNetherTerrainSystem", TerrainSystemType.class)
                        .comment("en_us", "Terrain system for the nether generation.")
                        .comment("zh_cn", "下界地形系统。")
                        .Default(TerrainSystemType.VOID)
                        .build();
        public static final TerrainSystemType netherTerrainSystem;

        public static final ConfigEntry<TerrainSystemType> END_TERRAIN_SYSTEM = Config
                        .setEnum("theEndTerrainSystem", TerrainSystemType.class)
                        .comment("en_us", "Terrain system for the end generation.")
                        .comment("zh_cn", "末地地形系统。")
                        .Default(TerrainSystemType.VOID)
                        .build();
        public static final TerrainSystemType endTerrainSystem;

        public static final ConfigEntry<BiomeSystemType> OVERWORLD_BIOME_SYSTEM = Config
                        .setEnum("overworldBiomeSystem", BiomeSystemType.class)
                        .comment("en_us", "Biome system for overworld biomes.")
                        .comment("zh_cn", "主世界群系系统。")
                        .Default(BiomeSystemType.VOID)
                        .build();
        public static final BiomeSystemType overworldBiomeSystem;

        public static final ConfigEntry<BiomeSystemType> NETHER_BIOME_SYSTEM = Config
                        .setEnum("theNetherBiomeSystem", BiomeSystemType.class)
                        .comment("en_us", "Biome system for the nether biomes.")
                        .comment("zh_cn", "下界群系系统。")
                        .Default(BiomeSystemType.VOID)
                        .build();
        public static final BiomeSystemType netherBiomeSystem;

        public static final ConfigEntry<BiomeSystemType> END_BIOME_SYSTEM = Config
                        .setEnum("theEndBiomeSystem", BiomeSystemType.class)
                        .comment("en_us", "Biome system for the end biomes.")
                        .comment("zh_cn", "末地群系系统。")
                        .Default(BiomeSystemType.VOID)
                        .build();
        public static final BiomeSystemType endBiomeSystem;

        public static final ConfigEntry<SurfaceSystemType> OVERWORLD_SURFACE_SYSTEM = Config
                        .setEnum("overworldSurfaceSystem", SurfaceSystemType.class)
                        .comment("en_us", "Surface system for overworld terrain.")
                        .comment("zh_cn", "主世界地表系统。")
                        .Default(SurfaceSystemType.VOID)
                        .build();
        public static final SurfaceSystemType overworldSurfaceSystem;

        public static final ConfigEntry<SurfaceSystemType> NETHER_SURFACE_SYSTEM = Config
                        .setEnum("theNetherSurfaceSystem", SurfaceSystemType.class)
                        .comment("en_us", "Surface system for the nether terrain.")
                        .comment("zh_cn", "下界地表系统。")
                        .Default(SurfaceSystemType.VOID)
                        .build();
        public static final SurfaceSystemType netherSurfaceSystem;

        public static final ConfigEntry<SurfaceSystemType> END_SURFACE_SYSTEM = Config
                        .setEnum("theEndSurfaceSystem", SurfaceSystemType.class)
                        .comment("en_us", "Surface system for the end terrain.")
                        .comment("zh_cn", "末地地表系统。")
                        .Default(SurfaceSystemType.VOID)
                        .build();
        public static final SurfaceSystemType endSurfaceSystem;

        public static final ConfigEntry<CarverSystemType> OVERWORLD_CARVER_SYSTEM = Config
                        .setEnum("overworldCarverSystem", CarverSystemType.class)
                        .comment("en_us", "Carver system for overworld terrain.")
                        .comment("zh_cn", "主世界雕刻系统。")
                        .Default(CarverSystemType.VOID)
                        .build();
        public static final CarverSystemType overworldCarverSystem;

        public static final ConfigEntry<CarverSystemType> NETHER_CARVER_SYSTEM = Config
                        .setEnum("theNetherCarverSystem", CarverSystemType.class)
                        .comment("en_us", "Carver system for the nether terrain.")
                        .comment("zh_cn", "下界雕刻系统。")
                        .Default(CarverSystemType.VOID)
                        .build();
        public static final CarverSystemType netherCarverSystem;

        public static final ConfigEntry<CarverSystemType> END_CARVER_SYSTEM = Config
                        .setEnum("theEndCarverSystem", CarverSystemType.class)
                        .comment("en_us", "Carver system for the end terrain.")
                        .comment("zh_cn", "末地雕刻系统。")
                        .Default(CarverSystemType.VOID)
                        .build();
        public static final CarverSystemType endCarverSystem;

        // 地形管线

        public static final ConfigEntry<Integer> MAX_GEN_TASKS_PER_TICK = Config.setInt("maxGenTasksPerTick")
                        .comment("en_us", "Max terrain-pipeline gen tasks consumed per wake.")
                        .comment("zh_cn", "地形管线每次唤醒消费的生成任务上限。")
                        .range(1, 65536)
                        .Default(2000)
                        .build();
        public static final int maxGenTasksPerTick;

        public static final ConfigEntry<Integer> GEN_WORKER_THREADS = Config.setInt("genWorkerThreads")
                        .comment("en_us",
                                        "Terrain generation worker threads. auto = half of the CPU logical processors (min 1); N = exactly N threads.")
                        .comment("zh_cn",
                                        "地形生成线程数。auto = CPU 逻辑线程数的一半（最小 1）；N = 恰好 N 个线程。")
                        .range(1, 64)
                        .auto(FarlandsConfig::autoThreads)
                        .Default("auto")
                        .build();
        public static final int genWorkerThreads;

        // 光照

        public static final ConfigEntry<Integer> PARALLEL_LIGHT_THREADS = Config.setInt("parallelLightThreads")
                        .comment("en_us",
                                        "Server-side light propagation threads. auto = half of the CPU logical processors (min 1); N = exactly N threads.")
                        .comment("zh_cn",
                                        "服务端光照传播线程数。auto = CPU 逻辑线程数的一半（最小 1）；N = 恰好 N 个线程。")
                        .range(1, 64)
                        .auto(FarlandsConfig::autoThreads)
                        .Default("auto")
                        .build();
        public static final int parallelLightThreads;

        public static final ConfigEntry<Integer> MAX_LIGHT_TASKS_PER_TICK = Config.setInt("maxLightTasksPerTick")
                        .comment("en_us",
                                        "Max light propagation tasks submitted per wake (each server tick wakes the drain loop once).")
                        .comment("zh_cn",
                                        "每次唤醒提交的光照传播任务上限（服务端每 tick 唤醒一次 drain 循环）。")
                        .range(1, 64)
                        .Default(4)
                        .build();
        public static final int maxLightTasksPerTick;

        public static final ConfigEntry<Integer> SECTION_SEND_BYTES_PER_TICK = Config.setInt("sectionSendBytesPerTick")
                        .comment("en_us",
                                        "Max window-slide §5 section bytes sent per tick per player.")
                        .comment("zh_cn",
                                        "每 tick 每玩家发送的 §5 窗口滑动 section 最大字节数。")
                        .range(1024, 1048576)
                        .Default(131072)
                        .build();
        public static final int sectionSendBytesPerTick;

        static {
                // 唯一读盘点：读一次、解析一次、约束一次。本类初始化由首个引用它的类触发。
                Config.init();
                borderAbsoluteMax = BORDER_ABSOLUTE_MAX.get();
                worldGenMinY = WORLD_GEN_MIN_Y.get();
                worldGenMaxY = WORLD_GEN_MAX_Y.get();
                maxCapIter = MAX_CAP_ITER.get();
                verticalSimulationDistance = VERTICAL_SIMULATION_DISTANCE.get();
                fsaCleanupMargin = FSA_CLEANUP_MARGIN.get();
                fsaCacheLimit = FSA_CACHE_LIMIT.get();
                fsaPersistInterval = FSA_PERSIST_INTERVAL.get();
                overworldTerrainSystem = OVERWORLD_TERRAIN_SYSTEM.get();
                netherTerrainSystem = NETHER_TERRAIN_SYSTEM.get();
                endTerrainSystem = END_TERRAIN_SYSTEM.get();
                overworldBiomeSystem = OVERWORLD_BIOME_SYSTEM.get();
                netherBiomeSystem = NETHER_BIOME_SYSTEM.get();
                endBiomeSystem = END_BIOME_SYSTEM.get();
                overworldSurfaceSystem = OVERWORLD_SURFACE_SYSTEM.get();
                netherSurfaceSystem = NETHER_SURFACE_SYSTEM.get();
                endSurfaceSystem = END_SURFACE_SYSTEM.get();
                overworldCarverSystem = OVERWORLD_CARVER_SYSTEM.get();
                netherCarverSystem = NETHER_CARVER_SYSTEM.get();
                endCarverSystem = END_CARVER_SYSTEM.get();
                maxGenTasksPerTick = MAX_GEN_TASKS_PER_TICK.get();
                genWorkerThreads = GEN_WORKER_THREADS.get();
                parallelLightThreads = PARALLEL_LIGHT_THREADS.get();
                maxLightTasksPerTick = MAX_LIGHT_TASKS_PER_TICK.get();
                sectionSendBytesPerTick = SECTION_SEND_BYTES_PER_TICK.get();
        }
}
