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

        static {
                Config.init();
                borderAbsoluteMax = BORDER_ABSOLUTE_MAX.get();
                worldGenMinY = WORLD_GEN_MIN_Y.get();
                worldGenMaxY = WORLD_GEN_MAX_Y.get();
                maxCapIter = MAX_CAP_ITER.get();
                verticalSimulationDistance = VERTICAL_SIMULATION_DISTANCE.get();
        }
}
