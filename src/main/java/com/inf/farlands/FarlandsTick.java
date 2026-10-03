package com.inf.farlands;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.serialize.ChunkReadiness;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.LevelSystems;
import com.inf.farlands.terrain.decorationFiller.DecorationFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.terrain.structure.StructureDriver;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173.Beta173NoiseSystem;
import com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla.VanillaNoiseSystem;
import com.inf.farlands.util.maps.AquiferUtil;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.SectionUtil;
import com.inf.farlands.util.network.ChunkDataSender;
import com.inf.farlands.util.network.SystemsSender;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class FarlandsTick {
    private static final int INTERVAL = 200;
    private static volatile int now = 0;

    public static int getNow() {
        return now;
    }

    /**
     * 单一时钟的写入点：服务端 tick 与分离 JVM 的客户端 tick 都从这里推进，侧信道打戳读的
     * 就是这个值。
     */
    public static void setNow(int tickCount) {
        now = tickCount;
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
     * <p>
     * 不能在 VanillaNoiseSystem 构造时发：它跑在 createLevels 里，那时玩家还没进世界，
     * 专用服务器上第一个玩家可能在播种之后很久才登录。这里每 tick 检查，按完成前后各读一次
     * isDone 判定，只在完成的那一 tick 给一次经验。
     */
    private static void awardNewVanillaNoiseSystem(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("new-vanilla-noise-system"));
        if (holder == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (((LevelSystems) level).terrainSystem() instanceof VanillaNoiseSystem) {
                for (ServerPlayer player : level.players()) {
                    PlayerAdvancements advancements = player.getAdvancements();
                    AdvancementProgress progress = advancements.getOrStartProgress(holder);
                    boolean wasDone = progress.isDone();

                    advancements.award(holder, "impossible");
                    if (!wasDone && progress.isDone()) {
                        player.giveExperiencePoints(64);
                    }
                }
            }
        }
    }

    /**
     * skys-the-limit 的三项判定与经验：系统是 beta173、顶部渐消关闭、玩家 Y 达到阈值。
     *
     * <p>
     * 三项各是一条 criterion，在同一份进度里各自成一组，都须达成。坐标判定放在 beta173 这一支之内，
     * 到该 Y 与在该系统下因此是同一瞬间成立。
     *
     * <p>
     * 经验在完成的那一 tick 给一次。award 的返回值只说那一条 criterion 是不是新授予的，三条里任何
     * 一条都可能早于完成，所以按完成前后各读一次 isDone 判定，与 vanilla 授予 rewards 的判据同形。
     */
    private static void awardSkyLimit(MinecraftServer server) {
        AdvancementHolder holder = server.getAdvancements().get(InfsFarlands.id("skys-the-limit"));
        if (holder == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (((LevelSystems) level).terrainSystem() instanceof Beta173NoiseSystem beta173) {
                for (ServerPlayer player : level.players()) {
                    PlayerAdvancements advancements = player.getAdvancements();
                    AdvancementProgress progress = advancements.getOrStartProgress(holder);
                    boolean wasDone = progress.isDone();

                    advancements.award(holder, "beta173_system");
                    if (!beta173.topFadeEnabled()) {
                        advancements.award(holder, "top_fade_disabled");
                    }
                    if (player.getY() >= 2008131844) {
                        advancements.award(holder, "y_above_threshold");
                    }
                    if (!wasDone && progress.isDone()) {
                        player.giveExperiencePoints(171103);
                    }
                }
            }
        }
    }

    /** 服务端 tick 末尾统一入口。 */
    public static void atEnd(MinecraftServer server, int tickCount) {
        setNow(tickCount);
        awardNewVanillaNoiseSystem(server);
        awardSkyLimit(server);
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
        // 世界系统选择下发：玩家进服或换维度后补一次，客户端据此显示当前 level 用哪四族系统。
        SystemsSender.tick(server);
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
        // 每 tick 唤醒编码消费者：编码在 ENCODE_POOL 上做，主线程只做唤醒与记账。这里同时是被「写入者
        // 在途」挡回的单元的兜底唤醒来源，延迟上限一 tick。
        SectionLifecycle.tick();

        // 地形管线：每 tick 唤醒生成消费，不超过 maxGenTasksPerTick。
        GenQueue.tick();
        // 动态扫描：每 tick 从玩家当前位置螺旋扫描视距内未生成的 chunk 补入队。
        GenQueue.scanAndEnqueue(server);
        // 装饰驱动：判门、认领、提交到 farlands-gen。排在扫描之后，本 tick 新登记的项当轮就能走。
        DecorationFiller.tick();
        // 结构相驱动：重试那些在等 ±8 壳齐的 chunk，过了就跑挂起的续作。
        StructureDriver.tick();
        // 数据就绪驱动：当 tick 变成就绪的 chunk 在同一 tick 放行 promotion 的两个 future。
        ChunkReadiness.drive();
    }
}
