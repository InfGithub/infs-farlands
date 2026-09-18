package com.inf.farlands;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
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
        // §5 窗口差量 + 限量发包；返回值同时驱动 fsa 的窗口清理判定。
        boolean windowChanged = ChunkDataSender.tick(server);

        // fsa 序列化

        // 周期持久化：窗口内脏 section 写盘；偏移表与数据同节奏落盘，否则运行中磁盘偏移表
        // 陈旧，重进时 getSlot 错位丢 section。崩溃/强退兜底。
        if (tickCount % FarlandsConfig.fsaPersistInterval == 0) {
            SectionLifecycle.flushAllDirty(server);
            SectionIO.flushAllOffsetTables();
        }
        // 窗口变化：窗口并集加余量之外的 section 持久化后即删内存，上限 CLEANUP_BUDGET/tick。
        if (windowChanged) {
            SectionLifecycle.cleanup(server);
        }
        // 重进瞬间窗口未建立时加载的 chunk 读回兜底；内部 budget 32/tick、窗口空时零开销早退。
        if (tickCount % 5 == 0) {
            SectionLifecycle.retryPendingReads(server);
        }
        // 每 tick 编码消费：主线程现取现编码，预算 ENCODE_BUDGET。
        SectionLifecycle.tick();
    }
}
