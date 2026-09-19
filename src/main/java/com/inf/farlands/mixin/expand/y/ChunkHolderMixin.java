package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.network.expand.y.LightUpdatePacket;
import com.inf.farlands.network.expand.y.SectionBlocksUpdatePacket;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块变化与光照增量改用绝对 sectionY 记账。
 *
 * vanilla 的 changedBlocksPerSection 是长度等于
 * levelHeightAccessor.getSectionsCount() 的数组，
 * 下标来自 getSectionIndex(y)。这个 levelHeightAccessor 是 ServerLevel，不是 ChunkAccess，
 * 所以数组恒为维度高度那 24 格；而本 port 的窗口会跟着玩家滑到 -64 以下，负下标直接越界崩。
 * 光照那条同理：skyChangedLightSectionFilter 的位下标是 chunkY - minLightSection，范围只有
 * [-5,21]，窗口段落在外面时既越界也发不出去。
 *
 * 两处都改成以绝对 sectionY 为键，广播时用绝对坐标，并走自定义包：
 * 光照用 LightUpdatePacket，批量方块用 SectionBlocksUpdatePacket。后者是因为
 * vanilla 的 ClientboundSectionBlocksUpdatePacket 用 SectionPos.STREAM_CODEC
 * 位打包，超出打包
 * 位宽的 Y 会在客户端解出垃圾坐标，而 side-channel 是进程内的，跨进程不传递。
 *
 * 单块变化仍用 vanilla 的 ClientboundBlockUpdatePacket：它带 BlockPos 的 3int 绝对坐标，安全。
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    @Unique
    private final Map<Integer, ShortSet> farlands$changedSections = new HashMap<>();

    @Unique
    private final IntSet farlands$affectedSkySections = new IntOpenHashSet();

    @Unique
    private final IntSet farlands$affectedBlockSections = new IntOpenHashSet();

    @Shadow
    private boolean hasChangedSections;

    @Shadow
    private ChunkHolder.PlayerProvider playerProvider;

    @Shadow
    @Final
    private LevelLightEngine lightEngine;

    @Shadow
    @Final
    private java.util.BitSet skyChangedLightSectionFilter;

    @Shadow
    @Final
    private java.util.BitSet blockChangedLightSectionFilter;

    @Shadow
    public abstract LevelChunk getTickingChunk();

    @Shadow
    private void broadcast(List<ServerPlayer> players, Packet<?> packet) {
    }

    @Shadow
    private void broadcastBlockEntityIfNeeded(List<ServerPlayer> players, Level level, BlockPos pos,
            BlockState state) {
    }

    @Overwrite
    public boolean blockChanged(BlockPos pos) {
        LevelChunk chunk = this.getTickingChunk();
        if (chunk == null) {
            return false;
        }
        boolean had = this.hasChangedSections;
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        ShortSet set = this.farlands$changedSections.computeIfAbsent(sectionY, k -> new ShortOpenHashSet());
        set.add(SectionPos.sectionRelativePos(pos));
        this.hasChangedSections = true;
        return !had;
    }

    /**
     * 原方法体完整执行，它负责 markUnsaved 与自身的范围检查；返回值可能因范围外而为 false，
     * 所以这里在 RETURN 后按绝对 sectionY 追加收集，本方法的返回改为"是否有新的待广播"。
     */
    @Inject(method = "sectionLightChanged", at = @At("RETURN"), cancellable = true)
    private void farlands$collectLightSection(LightLayer layer, int sectionY, CallbackInfoReturnable<Boolean> cir) {
        if (this.getTickingChunk() == null) {
            return;
        }
        if (layer == LightLayer.SKY) {
            cir.setReturnValue(this.farlands$affectedSkySections.add(sectionY));
        } else {
            cir.setReturnValue(this.farlands$affectedBlockSections.add(sectionY));
        }
    }

    @Overwrite
    public void broadcastChanges(LevelChunk chunk) {
        if (!this.hasChangedSections
                && this.farlands$affectedSkySections.isEmpty()
                && this.farlands$affectedBlockSections.isEmpty()) {
            return;
        }

        Level level = chunk.getLevel();

        if (!this.farlands$affectedSkySections.isEmpty() || !this.farlands$affectedBlockSections.isEmpty()) {
            List<ServerPlayer> borderPlayers = this.playerProvider.getPlayers(chunk.getPos(), true);
            if (!borderPlayers.isEmpty()) {
                this.broadcast(borderPlayers, new ClientboundCustomPayloadPacket(
                        farlands$buildLightPacket(chunk)));
            }
            this.farlands$affectedSkySections.clear();
            this.farlands$affectedBlockSections.clear();
        }
        // vanilla 的位过滤由 sectionLightChanged 原方法体填充，这里无条件清空防残留。
        this.skyChangedLightSectionFilter.clear();
        this.blockChangedLightSectionFilter.clear();

        if (!this.hasChangedSections) {
            return;
        }

        List<ServerPlayer> players = this.playerProvider.getPlayers(chunk.getPos(), false);
        for (Map.Entry<Integer, ShortSet> entry : this.farlands$changedSections.entrySet()) {
            ShortSet changed = entry.getValue();
            if (changed.isEmpty() || players.isEmpty()) {
                continue;
            }
            SectionPos sectionPos = SectionPos.of(chunk.getPos(), entry.getKey());
            if (changed.size() == 1) {
                BlockPos pos = sectionPos.relativeToBlockPos(changed.iterator().nextShort());
                BlockState state = level.getBlockState(pos);
                this.broadcast(players, new ClientboundBlockUpdatePacket(pos, state));
                this.broadcastBlockEntityIfNeeded(players, level, pos, state);
            } else {
                LevelChunkSection section = ((WindowedChunk) chunk).windowedAllSections().get(entry.getKey());
                if (section == null) {
                    continue;
                }
                int size = changed.size();
                short[] positions = new short[size];
                BlockState[] states = new BlockState[size];
                int i = 0;
                for (short packed : changed) {
                    positions[i] = packed;
                    states[i] = section.getBlockState(
                            SectionPos.sectionRelativeX(packed),
                            SectionPos.sectionRelativeY(packed),
                            SectionPos.sectionRelativeZ(packed));
                    i++;
                }
                SectionBlocksUpdatePacket pkt = new SectionBlocksUpdatePacket(
                        level.dimension(), sectionPos, positions, states);
                this.broadcast(players, new ClientboundCustomPayloadPacket(pkt));
                pkt.runUpdates((p, st) -> this.broadcastBlockEntityIfNeeded(players, level, p, st));
            }
        }
        this.farlands$changedSections.clear();
        this.hasChangedSections = false;
    }

    @Unique
    private LightUpdatePacket farlands$buildLightPacket(LevelChunk chunk) {
        List<LightUpdatePacket.SectionLight> sky = farlands$buildLightLayer(
                chunk, LightLayer.SKY, this.farlands$affectedSkySections);
        List<LightUpdatePacket.SectionLight> block = farlands$buildLightLayer(
                chunk, LightLayer.BLOCK, this.farlands$affectedBlockSections);
        return new LightUpdatePacket(chunk.getLevel().dimension(),
                chunk.getPos().x(), chunk.getPos().z(), sky, block);
    }

    @Unique
    private List<LightUpdatePacket.SectionLight> farlands$buildLightLayer(LevelChunk chunk, LightLayer layer,
            IntSet affected) {
        List<Integer> sectionYs = new ArrayList<>(affected);
        Collections.sort(sectionYs);
        List<LightUpdatePacket.SectionLight> out = new ArrayList<>(sectionYs.size());
        for (int sy : sectionYs) {
            DataLayer dl = this.lightEngine.getLayerListener(layer)
                    .getDataLayerData(SectionPos.of(chunk.getPos(), sy));
            out.add(new LightUpdatePacket.SectionLight(sy,
                    LightUpdatePacket.encodeSectionLight(dl)));
        }
        return out;
    }
}
