package com.inf.farlands;

import java.util.Map;

import com.inf.farlands.terrain.system.biome.BiomeSystemType;
import com.inf.farlands.terrain.system.carver.CarverSystemType;
import com.inf.farlands.terrain.system.surface.SurfaceSystemType;
import com.inf.farlands.terrain.system.terrain.TerrainSystemType;
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

        public static final ConfigEntry<Integer> FSA_CACHE_LIMIT = Config.register(
                        "fsaCacheLimit",
                        int.class,
                        128,
                        Map.of("en_us", "Maximum number of open fsa files kept in cache.",
                                        "zh_cn", "打开的 fsa 文件缓存上限。"));
        public static final int fsaCacheLimit;

        public static final ConfigEntry<Long> FSA_PERSIST_INTERVAL = Config.register(
                        "fsaPersistInterval",
                        long.class,
                        6000L,
                        Map.of("en_us", "Interval in ticks between periodic persistence of dirty sections.",
                                        "zh_cn", "定期持久化脏 section 的间隔，单位 tick。"));
        public static final long fsaPersistInterval;

        // 地形系统

        public static final ConfigEntry<TerrainSystemType> OVERWORLD_TERRAIN_SYSTEM = Config.register(
                        "overworldTerrainSystem",
                        TerrainSystemType.class,
                        TerrainSystemType.VOID,
                        Map.of("en_us", "Terrain system for overworld generation.", "zh_cn", "主世界地形系统。"));
        public static final TerrainSystemType overworldTerrainSystem;

        public static final ConfigEntry<TerrainSystemType> NETHER_TERRAIN_SYSTEM = Config.register(
                        "theNetherTerrainSystem",
                        TerrainSystemType.class,
                        TerrainSystemType.VOID,
                        Map.of("en_us", "Terrain system for the nether generation.", "zh_cn", "下界地形系统。"));
        public static final TerrainSystemType netherTerrainSystem;

        public static final ConfigEntry<TerrainSystemType> END_TERRAIN_SYSTEM = Config.register(
                        "theEndTerrainSystem",
                        TerrainSystemType.class,
                        TerrainSystemType.VOID,
                        Map.of("en_us", "Terrain system for the end generation.", "zh_cn", "末地地形系统。"));
        public static final TerrainSystemType endTerrainSystem;

        public static final ConfigEntry<BiomeSystemType> OVERWORLD_BIOME_SYSTEM = Config.register(
                        "overworldBiomeSystem",
                        BiomeSystemType.class,
                        BiomeSystemType.VOID,
                        Map.of("en_us", "Biome system for overworld biomes.", "zh_cn", "主世界群系系统。"));
        public static final BiomeSystemType overworldBiomeSystem;

        public static final ConfigEntry<BiomeSystemType> NETHER_BIOME_SYSTEM = Config.register(
                        "theNetherBiomeSystem",
                        BiomeSystemType.class,
                        BiomeSystemType.VOID,
                        Map.of("en_us", "Biome system for the nether biomes.", "zh_cn", "下界群系系统。"));
        public static final BiomeSystemType netherBiomeSystem;

        public static final ConfigEntry<BiomeSystemType> END_BIOME_SYSTEM = Config.register(
                        "theEndBiomeSystem",
                        BiomeSystemType.class,
                        BiomeSystemType.VOID,
                        Map.of("en_us", "Biome system for the end biomes.", "zh_cn", "末地群系系统。"));
        public static final BiomeSystemType endBiomeSystem;

        public static final ConfigEntry<SurfaceSystemType> OVERWORLD_SURFACE_SYSTEM = Config.register(
                        "overworldSurfaceSystem",
                        SurfaceSystemType.class,
                        SurfaceSystemType.VOID,
                        Map.of("en_us", "Surface system for overworld terrain.", "zh_cn", "主世界地表系统。"));
        public static final SurfaceSystemType overworldSurfaceSystem;

        public static final ConfigEntry<SurfaceSystemType> NETHER_SURFACE_SYSTEM = Config.register(
                        "theNetherSurfaceSystem",
                        SurfaceSystemType.class,
                        SurfaceSystemType.VOID,
                        Map.of("en_us", "Surface system for the nether terrain.", "zh_cn", "下界地表系统。"));
        public static final SurfaceSystemType netherSurfaceSystem;

        public static final ConfigEntry<SurfaceSystemType> END_SURFACE_SYSTEM = Config.register(
                        "theEndSurfaceSystem",
                        SurfaceSystemType.class,
                        SurfaceSystemType.VOID,
                        Map.of("en_us", "Surface system for the end terrain.", "zh_cn", "末地地表系统。"));
        public static final SurfaceSystemType endSurfaceSystem;

        public static final ConfigEntry<CarverSystemType> OVERWORLD_CARVER_SYSTEM = Config.register(
                        "overworldCarverSystem",
                        CarverSystemType.class,
                        CarverSystemType.VOID,
                        Map.of("en_us", "Carver system for overworld terrain.", "zh_cn", "主世界雕刻系统。"));
        public static final CarverSystemType overworldCarverSystem;

        public static final ConfigEntry<CarverSystemType> NETHER_CARVER_SYSTEM = Config.register(
                        "theNetherCarverSystem",
                        CarverSystemType.class,
                        CarverSystemType.VOID,
                        Map.of("en_us", "Carver system for the nether terrain.", "zh_cn", "下界雕刻系统。"));
        public static final CarverSystemType netherCarverSystem;

        public static final ConfigEntry<CarverSystemType> END_CARVER_SYSTEM = Config.register(
                        "theEndCarverSystem",
                        CarverSystemType.class,
                        CarverSystemType.VOID,
                        Map.of("en_us", "Carver system for the end terrain.", "zh_cn", "末地雕刻系统。"));
        public static final CarverSystemType endCarverSystem;

        // 地形管线

        public static final ConfigEntry<Integer> MAX_GEN_TASKS_PER_TICK = Config.register(
                        "maxGenTasksPerTick",
                        int.class,
                        2000,
                        Map.of("en_us",
                                        "Max terrain-pipeline gen tasks consumed per wake.",
                                        "zh_cn",
                                        "地形管线每次唤醒消费的生成任务上限。"));
        public static final int maxGenTasksPerTick;

        public static final ConfigEntry<Integer> GEN_WORKER_THREADS = Config.register(
                        "genWorkerThreads",
                        int.class,
                        0,
                        Map.of("en_us",
                                        "Terrain generation worker threads. 0 = auto: half of CPU logical processors; 1 = single background thread; N = exactly N threads, max 64.",
                                        "zh_cn",
                                        "地形生成线程数。0 = 自动取 CPU 逻辑线程数一半；1 = 单个后台线程；N = 恰好 N 个线程，上限 64。"));
        public static final int genWorkerThreads;

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
                fsaCacheLimit = resolveFsaCacheLimit(FSA_CACHE_LIMIT.get());
                fsaPersistInterval = resolveFsaPersistInterval(FSA_PERSIST_INTERVAL.get());
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
                maxGenTasksPerTick = resolveMaxGenTasksPerTick(MAX_GEN_TASKS_PER_TICK.get());
                genWorkerThreads = GEN_WORKER_THREADS.get();
                parallelLightThreads = resolveLightThreads(PARALLEL_LIGHT_THREADS.get());
                maxLightTasksPerTick = MAX_LIGHT_TASKS_PER_TICK.get();
                sectionSendBytesPerTick = SECTION_SEND_BYTES_PER_TICK.get();
        }

        /** 生成任务上限下限 1：0 会让 drainGen 每轮一个任务都不消费。配置系统无范围校验，就地钳制。 */
        private static int resolveMaxGenTasksPerTick(int raw) {
                return Math.max(1, raw);
        }

        private static int resolveLightThreads(int raw) {
                if (raw <= 0) {
                        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                }
                return Math.min(64, raw);
        }

        /** fsa 文件缓存上限下限 1：0/负值会让 LinkedHashMap 构造抛异常。配置系统无范围校验，就地钳制。 */
        private static int resolveFsaCacheLimit(int raw) {
                return Math.max(1, raw);
        }

        /** 周期持久化间隔下限 1：0 会让 FarlandsTick 的取模除零。配置系统无范围校验，就地钳制。 */
        private static long resolveFsaPersistInterval(long raw) {
                return Math.max(1L, raw);
        }
}
