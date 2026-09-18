package com.inf.farlands;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.util.maps.AquiferUtil;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;
import com.inf.farlands.util.network.ChunkDataSender;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class FarlandsTick {
    private static final int INTERVAL = 200;
    private static volatile int now = 0;

    public static int getNow() {
        return now;
    }

    private static void swapBlockLookup(int tickCount) {
        int size = BlockUtil.size();
        InfSFarlands.LOGGER.info("Swapping BlockUtil.lookup, size: {}", size);
        BlockUtil.swap();
    }

    public static void trimSectionLookup(int tickCount) {
        int beforeSize = SectionUtil.size();
        SectionUtil.trim(tickCount);
        int afterSize = SectionUtil.size();
        InfSFarlands.LOGGER.info("Trimmed SectionUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
    }

    private static void trimAquiferLookup(int tickCount) {
        int beforeSize = AquiferUtil.size();
        AquiferUtil.trim(tickCount);
        int afterSize = AquiferUtil.size();
        InfSFarlands.LOGGER.info("Trimmed AquiferUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
    }

    /** 服务端 tick 末尾统一入口。 */
    public static void atEnd(MinecraftServer server, int tickCount) {
        now = tickCount;
        if (tickCount % INTERVAL == 0) {
            swapBlockLookup(tickCount);
            trimSectionLookup(tickCount);
            trimAquiferLookup(tickCount);
        }
        // 光照引擎每 tick 的任务配额：真 tick 是唯一权威边界。
        // 无 tick 阶段（prepareLevels 建世界 / saveEverything 保存）由引擎自己按 tick 间隔兜底。
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
                lightEngine.grantTickBudget();
            }
        }
        ChunkDataSender.tick(server);
    }
}
