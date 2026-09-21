package com.inf.farlands;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.util.maps.AquiferUtil;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class FarlandsTick {
    private static final int INTERVAL = 200;
    private static volatile int now = 0;

    public static int getNow() {
        return now;
    }

    private static void swapBlockLookup(MinecraftServer server, int tickCount) {
        // size 必须在 swap 前取：swap 之后读到的是新的空表，恒为 0。
        int size = BlockUtil.size();
        InfsFarlands.LOGGER.info("Swapping BlockUtil.lookup, size: {}", size);
        BlockUtil.swap();
        if (size > 0) {
            awardIntBlockPos(server);
        }
    }

    /**
     * 这一轮至少注册过一个坐标（swap 前的 size &gt; 0）时，给所有在线玩家授予成就。
     * 对应进度的 criterion 是 minecraft:impossible，永不自动满足，只能由本方法授予。
     * award 对已解锁的玩家是幂等的。
     */
    private static void awardIntBlockPos(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("int-block-pos"));
        if (holder == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.getAdvancements().award(holder, "impossible");
        }
    }

    public static void trimSectionLookup(int tickCount) {
        int beforeSize = SectionUtil.size();
        SectionUtil.trim(tickCount);
        int afterSize = SectionUtil.size();
        InfsFarlands.LOGGER.info("Trimmed SectionUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
    }

    private static void trimAquiferLookup(int tickCount) {
        int beforeSize = AquiferUtil.size();
        AquiferUtil.trim(tickCount);
        int afterSize = AquiferUtil.size();
        InfsFarlands.LOGGER.info("Trimmed AquiferUtil.lookup, before: {}, after: {}", beforeSize, afterSize);
    }

    /**
     * 该维度出现 vanilla 噪声系统时，给还在世上的玩家补发进度与经验。
     *
     * <p>不能在 VanillaNoiseSystem 构造时发：它跑在 createLevels 里，那时玩家还没进世界，
     * 专用服务器上第一个玩家可能在播种之后很久才登录。这里每 tick 检查、用 award 的返回值
     * 兜住「只发一次」，因此不需要记「发过没」的状态：
     * award 对已解锁玩家返回 false，自然就不重复给经验。
     */
    private static void awardNewVanillaNoiseSystem(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("new-vanilla-noise-system"));
        if (holder == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!(((LevelSystems) level).terrainSystem() instanceof VanillaNoiseSystem)) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                if (player.getAdvancements().award(holder, "impossible")) {
                    player.giveExperiencePoints(64);
                }
            }
        }
    }

    /** 服务端 tick 末尾统一入口。 */
    public static void atEnd(MinecraftServer server, int tickCount) {
        now = tickCount;
        awardNewVanillaNoiseSystem(server);
        if (tickCount % INTERVAL == 0) {
            swapBlockLookup(server, tickCount);
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
        // P2 动态优先级：任一玩家跨 chunk 移动时，地形与光照队列按当前距离重排。
        if (ChunkDataSender.consumePlayersMoved()) {
            GenQueue.rebuildQueue();
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getChunkSource().getLightEngine() instanceof FarLandsLightEngine lightEngine) {
                    lightEngine.rebuildLightQueue();
                }
            }
        }
        // 实体 section 窗口并集更新。terrain 的窗口段收集与 fsa 的清理判定都读它，必须先刷新。
        EntitySectionWindow.update(server.getPlayerList().getPlayers());

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

        // 地形管线：每 tick 唤醒生成消费，不超过 maxGenTasksPerTick。
        GenQueue.tick();
        // 动态扫描：每 tick 从玩家当前位置螺旋扫描视距内未生成的 chunk 补入队。
        GenQueue.scanAndEnqueue(server);
    }
}
