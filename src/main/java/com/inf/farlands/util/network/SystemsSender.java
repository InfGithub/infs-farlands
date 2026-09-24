package com.inf.farlands.util.network;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.network.systems.SystemsPacket;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsHolder;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 每个玩家一条的维度系统选择下发。
 *
 * <p>选择在 level 建好之后不再变，所以只在玩家进服或换维度时发一次：记下每人上次发出的维度，与当前
 * 维度不同才发。进服那次天然不同，因此不需要玩家加入的事件钩子，本 port 也没有那个钩子。
 *
 * <p>玩家退出靠每 tick 的名单比对清理，与 {@link ChunkDataSender} 同形。
 */
public final class SystemsSender {

    private SystemsSender() {
    }

    private static final Map<UUID, ResourceKey<Level>> SENT_DIMENSIONS = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            ResourceKey<Level> dimension = player.level().dimension();
            if (dimension.equals(SENT_DIMENSIONS.get(player.getUUID()))) {
                continue;
            }
            ServerLevel level = player.level();
            SystemsData data = ((SystemsHolder) level).systems();
            SystemsData.LevelSelection selection = SystemsIO.selection(data, dimension.identifier());
            player.connection.send(new ClientboundCustomPayloadPacket(
                    new SystemsPacket(dimension, selection)));
            SENT_DIMENSIONS.put(player.getUUID(), dimension);
        }
        Set<UUID> roster = new HashSet<>();
        for (ServerPlayer player : players) {
            roster.add(player.getUUID());
        }
        SENT_DIMENSIONS.keySet().retainAll(roster);
    }
}
