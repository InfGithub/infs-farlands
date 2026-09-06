package com.inf.farlands.client.mixin.expand.y;

import com.inf.farlands.util.maps.Common;
import com.inf.farlands.util.window.WindowedChunk;
// import com.inf.farlands.light.FarLandsLightEngine;
// import com.inf.farlands.light.FarLandsLightPacketData;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.PacketUtils;

@Mixin(value = ClientPacketListener.class, priority = 100)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Overwrite
    public void handleChunksBiomes(ClientboundChunksBiomesPacket packet) {
        PacketUtils.ensureRunningOnSameThread(
                packet,
                (ClientPacketListener) (Object) this,
                Minecraft.getInstance().packetProcessor());

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            this.level
                    .getChunkSource()
                    .replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            this.level.onChunkLoaded(new ChunkPos(data.pos().x(), data.pos().z()));
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    ChunkAccess ca = this.level.getChunkSource().getChunk(
                            data.pos().x() + i, data.pos().z() + j, ChunkStatus.FULL, false);
                    if (ca instanceof LevelChunk c) {
                        for (Integer sectionY : ((WindowedChunk) c).windowedAllSections().keySet()) {
                            Minecraft.getInstance().levelRenderer.setSectionDirty(
                                    data.pos().x() + i, sectionY, data.pos().z() + j);
                        }
                    }
                }
            }
        }
    }

    @Overwrite
    public void handleForgetLevelChunk(ClientboundForgetLevelChunkPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet,
                (ClientPacketListener) (Object) this, Minecraft.getInstance().packetProcessor());
        ChunkPos cpos = packet.pos();

        int[] sectionYs = null;
        ChunkAccess ca = this.level.getChunkSource().getChunk(cpos.x(), cpos.z(), ChunkStatus.FULL, false);
        if (ca instanceof LevelChunk c) {
            java.util.Set<Integer> keys = ((WindowedChunk) c).windowedAllSections().keySet();
            sectionYs = new int[keys.size()];
            int idx = 0;
            for (Integer sy : keys) {
                sectionYs[idx++] = sy;
            }
        }
        final int[] ys = sectionYs;

        Common.discardPendingSectionData(this.level.dimension(), cpos);

        this.level.getChunkSource().drop(cpos);

        this.level.queueLightUpdate(() -> {
            LevelLightEngine le = this.level.getLightEngine();
            le.setLightEnabled(cpos, false);
            if (ys != null) {
                for (int sy : ys) {
                    le.queueSectionData(LightLayer.BLOCK, SectionPos.of(cpos, sy), null);
                    le.queueSectionData(LightLayer.SKY, SectionPos.of(cpos, sy), null);
                }
                for (int sy : ys) {
                    le.updateSectionStatus(SectionPos.of(cpos, sy), true);
                }
            } else {
                for (int i = le.getMinLightSection(); i < le.getMaxLightSection(); i++) {
                    le.queueSectionData(LightLayer.BLOCK, SectionPos.of(cpos, i), null);
                    le.queueSectionData(LightLayer.SKY, SectionPos.of(cpos, i), null);
                }
                for (int j = this.level.getMinSectionY(); j <= this.level.getMaxSectionY(); j++) {
                    le.updateSectionStatus(SectionPos.of(cpos, j), true);
                }
            }
        });
    }

    @Overwrite
    private void enableChunkLight(LevelChunk chunk, int x, int z) {
        LevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
        ChunkPos chunkPos = chunk.getPos();
        for (Map.Entry<Integer, LevelChunkSection> e : ((WindowedChunk) chunk).windowedAllSections().entrySet()) {
            LevelChunkSection section = e.getValue();
            if (section == null) {
                continue;
            }
            int sectionY = e.getKey();
            SectionPos secPos = SectionPos.of(chunkPos, sectionY);
            boolean air = section.hasOnlyAir();
            lightEngine.updateSectionStatus(secPos, air);
            this.level.setSectionDirtyWithNeighbors(x, sectionY, z);
        }
    }


    // InfFarlands.applyPendingSectionData(this.level.dimension(), packet.getX(),
    // packet.getZ());
    // }
}