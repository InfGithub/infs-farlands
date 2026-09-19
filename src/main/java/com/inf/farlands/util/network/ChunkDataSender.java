package com.inf.farlands.util.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.network.expand.y.LightUpdatePacket;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.serialize.TerrainHooks;
import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.inf.farlands.terrain.pipeline.GenQueue;
import com.inf.farlands.util.window.WindowedChunk;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * §5 窗口滑动 section 包的发送端。
 *
 * <p>
 * 服务端主线程每 tick：窗口差量检测；限量发包。
 *
 * <p>
 * 窗口 = 玩家 sectionY ± {@link FarlandsConfig#verticalSimulationDistance}，
 * 与 axisY 的窗口/实体窗口/随机刻窗口同源。section 数据以 byte[] 中转：装载
 * 是否发送由接收端按包内绝对 sectionY 与 chunk 当前状态决定。
 *
 * <p>
 * 状态全部主线程，故队列用普通集合即可；但 {@link #enqueueSectionSend}
 * 可能被生成线程调用，故 pendingQueues 用 ConcurrentHashMap。
 */
public final class ChunkDataSender {

    private ChunkDataSender() {
    }

    /** per-player 窗口状态。 */
    private static final class PlayerWindowState {
        int centerY;
        ResourceKey<Level> dimension;
        /** 上次 XZ chunk 坐标，P2 动态优先级触发检测用。 */
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;

        PlayerWindowState(int centerY, ResourceKey<Level> dimension) {
            this.centerY = centerY;
            this.dimension = dimension;
        }
    }

    /** UUID -> 上次窗口中心与维度。维度变化按首次记录语义处理，不发 difference。 */
    private static final Map<UUID, PlayerWindowState> WINDOW_STATES = new ConcurrentHashMap<>();

    /** §4.2 队列：UUID -> (chunkPos -> 新进入 sectionY 集合)。已发送即出队，队列无历史状态机。 */
    private static final Map<UUID, Map<Long, IntSet>> PENDING_QUEUES = new ConcurrentHashMap<>();

    /** 玩家退出清理，防状态随会话永存。 */
    public static void onPlayerLogout(UUID id) {
        WINDOW_STATES.remove(id);
        PENDING_QUEUES.remove(id);
    }

    /** 本 tick 是否有玩家跨 chunk 移动，驱动地形与光照队列按当前距离重排。 */
    private static volatile boolean playersMoved;

    /** 取走并清除玩家移动标志。 */
    public static boolean consumePlayersMoved() {
        boolean moved = playersMoved;
        playersMoved = false;
        return moved;
    }

    /**
     * 服务端每 tick 入口：窗口差量 + 限量发包。
     * 返回 true 表示任一玩家窗口发生变化，调用方 FarlandsTick 据此驱动 fsa 清理判定。
     */
    public static boolean tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        boolean windowChanged = false;
        playersMoved = false;
        for (ServerPlayer player : players) {
            if (updatePlayerWindow(player)) {
                windowChanged = true;
            }
        }
        for (ServerPlayer player : players) {
            flushPendingSections(player);
        }
        // 玩家退出后清理窗口状态（roster 变化在下一次 tick 体现）
        Set<UUID> roster = new HashSet<>();
        for (ServerPlayer p : players) {
            roster.add(p.getUUID());
        }
        WINDOW_STATES.keySet().retainAll(roster);
        PENDING_QUEUES.keySet().retainAll(roster);
        return windowChanged;
    }

    /**
     * 窗口变化检测 + difference：新窗口内不在旧窗口区间的 sectionY = 新进入 -> 入队该玩家
     * tracking view 内每个 chunk。首次记录（或换维度）整窗入队。
     *
     * <p>
     * 返回 true = 窗口变化。
     */
    public static boolean updatePlayerWindow(ServerPlayer player) {
        ResourceKey<Level> dim = player.level().dimension();
        UUID id = player.getUUID();
        int centerY = Mth.floorDiv(player.getBlockY(), 16);
        PlayerWindowState state = WINDOW_STATES.get(id);

        if (state == null || !state.dimension.equals(dim)) {
            WINDOW_STATES.put(id, new PlayerWindowState(centerY, dim));
            int min = centerY - FarlandsConfig.verticalSimulationDistance;
            int max = centerY + FarlandsConfig.verticalSimulationDistance;
            for (int sy = min; sy <= max; sy++) {
                enqueueForWindow(player, sy);
            }
            return true;
        }
        // P2 动态优先级：任一玩家跨 chunk 移动时，地形与光照队列按当前距离重排。
        ChunkPos pc = player.chunkPosition();
        if (state.lastChunkX != pc.x() || state.lastChunkZ != pc.z()) {
            state.lastChunkX = pc.x();
            state.lastChunkZ = pc.z();
            playersMoved = true;
        }
        if (state.centerY == centerY) {
            return false;
        }
        int oldMin = state.centerY - FarlandsConfig.verticalSimulationDistance;
        int oldMax = state.centerY + FarlandsConfig.verticalSimulationDistance;
        int newMin = centerY - FarlandsConfig.verticalSimulationDistance;
        int newMax = centerY + FarlandsConfig.verticalSimulationDistance;
        for (int sy = newMin; sy <= newMax; sy++) {
            if (sy >= oldMin && sy <= oldMax) {
                continue;
            }
            enqueueForWindow(player, sy);
        }
        state.centerY = centerY;
        return true;
    }

    /**
     * 该 sectionY 入队到玩家 tracking view 内每个 chunk，并对已加载的 chunk 触发 fsa 读回。
     *
     * <p>
     * fsa 读回要在磁盘有数据时恢复 section、光照与 stage，并补发 section 包。它与 §5 发送
     * 复用同一次窗口差量检测。旧仓库这两件事同在 {@code InfFarlands.updatePlayerWindow} 的
     * {@code enqueueGenForSection} 里，即 §5 入队加 {@code SectionLifecycle.loadSection}，
     * 本 port 在此合并。只对 ChunkStatus.FULL 的 chunk 读回。
     */
    private static void enqueueForWindow(ServerPlayer player, int sectionY) {
        int s = sectionY;
        ServerLevel level = player.level();
        player.getChunkTrackingView().forEach(cp -> {
            PENDING_QUEUES
                    .computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(cp.pack(), k -> new IntArraySet())
                    .add(s);
            ChunkAccess ca = level.getChunk(cp.x(), cp.z(), ChunkStatus.FULL, false);
            if (ca instanceof LevelChunk lc) {
                // fsa 读回优先：磁盘有数据则恢复数据、光照与 stage 并补发，不重生成。读回完成后
                // 再补填 biome 并入生成队列，GenQueue.enqueue 的 isOrAfter 会跳过已读回的 section。
                SectionLifecycle.loadSection(lc, s, () -> {
                    BiomeFiller.fillSectionBiomes(level, lc, s);
                    TerrainHooks.enqueueGen(lc, s);
                });
            }
        });
    }

    /**
     * fill 完成后主动入队该 section，复用窗口差量发送链，flush 限量。
     * fill 直接写 section 不走 Level.setBlock -> 无 vanilla 广播；此处对 tracking 玩家
     * 补入发送队列，下 tick flush 发 §5 包。生成线程可调，故用 CHM。
     *
     * <p>
     * 距离判定不依赖 tracking view，其每 tick 更新，fill 完成时新加载 chunk 可能
     * 不在 tracking -> §5 漏发 -> 客户端空壳空缺/双端不同步。
     */
    public static void enqueueSectionSend(LevelChunk chunk, int sectionY) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        long chunkKey = cp.pack();
        for (ServerPlayer player : level.players()) {
            ChunkTrackingView view = player.getChunkTrackingView();
            int viewDistance = view instanceof ChunkTrackingView.Positioned pos ? pos.viewDistance() : 8;
            if (ChunkTrackingView.isWithinDistance(
                    player.chunkPosition().x(), player.chunkPosition().z(), viewDistance,
                    cp.x(), cp.z(), true)) {
                PENDING_QUEUES.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(chunkKey, k -> new IntArraySet())
                        .add(sectionY);
            }
        }
    }

    /** 限量发送：按距离排序出队，累计 ≤ sectionSendBytesPerTick，打包为一个 §5 包。 */
    private static void flushPendingSections(ServerPlayer player) {
        Map<Long, IntSet> queue = PENDING_QUEUES.get(player.getUUID());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        ChunkPos playerChunk = player.chunkPosition();
        int budget = FarlandsConfig.sectionSendBytesPerTick;
        int windowMinY = Mth.floorDiv(player.getBlockY(), 16) - FarlandsConfig.verticalSimulationDistance;

        List<Map.Entry<Long, IntSet>> sorted = new ArrayList<>(queue.entrySet());
        sorted.sort(Comparator.comparingLong(e -> distSq(e.getKey(), playerChunk)));

        List<ChunkDataPacket.SectionEntry> batch = new ArrayList<>();
        int used = 0;
        outer: for (Map.Entry<Long, IntSet> e : sorted) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk lc)) {
                continue;
            }
            if (GenQueue.isChunkBusy(lc)) {
                // 生成或光照在途：整块留队列，不读半成品 section。genPool 正在写 section 时
                // 序列化会撞 vanilla 的 PalettedContainer 多线程检测，直接 CTD。
                continue;
            }
            IntIterator it = e.getValue().iterator();
            while (it.hasNext()) {
                int sy = it.nextInt();
                LevelChunkSection section = ((WindowedChunk) lc).windowedAllSections().get(sy);
                if (section == null || section.hasOnlyAir()) {
                    it.remove(); // 空 section 无需发送
                    continue;
                }
                if (GenQueue.isLightInFlight(lc) && ((WindowedChunk) lc).isSectionDirty(sy)) {
                    // 生成 fill 的 section 光照播种未完成，留队列等光照。读回的 section 未脏，放行发送。
                    continue;
                }
                FriendlyByteBuf tmp = new FriendlyByteBuf(Unpooled.buffer());
                section.write(tmp);
                byte[] data = new byte[tmp.readableBytes()];
                tmp.readBytes(data);
                int est = 24 + data.length;
                if (used + est > budget) {
                    break outer; // 预算耗尽，剩余留队列
                }
                SectionPos spos = SectionPos.of(lc.getPos(), sy);
                LevelLightEngine le = level.getChunkSource().getLightEngine();
                DataLayer bl = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(spos);
                DataLayer sl = le.getLayerListener(LightLayer.SKY).getDataLayerData(spos);
                batch.add(new ChunkDataPacket.SectionEntry(
                        cx, cz, sy, data,
                        ChunkDataPacket.encodeLight(bl),
                        ChunkDataPacket.encodeLight(sl)));
                it.remove();
                used += est;
            }
            if (e.getValue().isEmpty()) {
                queue.remove(e.getKey());
            }
        }
        if (!batch.isEmpty()) {
            player.connection.send(new ClientboundCustomPayloadPacket(
                    new ChunkDataPacket(level.dimension(), windowMinY, batch)));
        }
    }

    private static long distSq(long chunkKey, ChunkPos playerChunk) {
        long dx = (long) ChunkPos.getX(chunkKey) - playerChunk.x();
        long dz = (long) ChunkPos.getZ(chunkKey) - playerChunk.z();
        return dx * dx + dz * dz;
    }

    /**
     * 播种完成后主动广播该 chunk 全部光照，含空 section 的 15。
     *
     * <p>
     * 增量包链依赖 chunk 达 ENTITY_TICKING，而空壳先发加光照后补的管线里播种时往往未达，
     * 客户端会保持邻居传播写入的渐黑。本方法绕开该依赖，播种完成即对 tracking 玩家发全窗口光照。
     */
    public static void broadcastChunkLight(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.getChunkTrackingView().contains(cp)) {
                players.add(p);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        LevelLightEngine le = level.getChunkSource().getLightEngine();
        List<LightUpdatePacket.SectionLight> sky = new ArrayList<>();
        List<LightUpdatePacket.SectionLight> block = new ArrayList<>();
        for (Integer sy : ((WindowedChunk) chunk).windowedAllSections().keySet()) {
            SectionPos spos = SectionPos.of(cp, sy);
            sky.add(new LightUpdatePacket.SectionLight(sy,
                    LightUpdatePacket.encodeSectionLight(
                            le.getLayerListener(LightLayer.SKY).getDataLayerData(spos))));
            block.add(new LightUpdatePacket.SectionLight(sy,
                    LightUpdatePacket.encodeSectionLight(
                            le.getLayerListener(LightLayer.BLOCK).getDataLayerData(spos))));
        }
        LightUpdatePacket pkt = new LightUpdatePacket(level.dimension(), cp.x(), cp.z(), sky, block);
        for (ServerPlayer p : players) {
            p.connection.send(new ClientboundCustomPayloadPacket(pkt));
        }
    }
}
