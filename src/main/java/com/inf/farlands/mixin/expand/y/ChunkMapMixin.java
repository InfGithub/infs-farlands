package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.window.WindowSendState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * biomes 重发按收件玩家各自的窗口发包。
 *
 * 服务端 chunk 窗口是构造默认的死状态，写侧靠 ThreadLocal 拿窗口；不设它就退回
 * chunk.getWindowMinY()，只发 -4 起的那一段，玩家窗口滑到别处时群系收不到。形制与
 * PlayerChunkSender 那条发送路径一致：发包前设窗口，finally 里清。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    public abstract List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyPlayersWithChunkTracked);

    @Overwrite
    public void resendBiomesForChunks(List<ChunkAccess> chunks) {
        Map<ServerPlayer, List<LevelChunk>> chunksForPlayers = new HashMap<>();

        for (ChunkAccess chunkAccess : chunks) {
            ChunkPos pos = chunkAccess.getPos();
            LevelChunk chunk;
            if (chunkAccess instanceof LevelChunk levelChunk) {
                chunk = levelChunk;
            } else {
                chunk = this.level.getChunk(pos.x(), pos.z());
            }

            for (ServerPlayer player : this.getPlayers(pos, false)) {
                chunksForPlayers.computeIfAbsent(player, p -> new ArrayList<>()).add(chunk);
            }
        }

        chunksForPlayers.forEach((player, chunkList) -> {
            int centerY = Mth.floorDiv(player.getBlockY(), 16);
            WindowSendState.setWindowMinY(centerY - FarlandsConfig.verticalSimulationDistance);
            try {
                player.connection.send(ClientboundChunksBiomesPacket.forChunks(chunkList));
            } finally {
                WindowSendState.clear();
            }
        });
    }
}
