package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Shadow
    private List<ServerPlayer> players;

    @Shadow
    public abstract void tickPrecipitation(BlockPos blockPos);

    @SuppressWarnings("resource")
    @Overwrite
    public void tickChunk(LevelChunk chunk, int randomTickSpeed) {
        Level level = (Level) (Object) this;
        ChunkPos chunkpos = chunk.getPos();
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        ProfilerFiller profilerfiller = Profiler.get();
        profilerfiller.push("iceandsnow");

        for (int i1 = 0; i1 < randomTickSpeed; i1++) {
            if (level.getRandom().nextInt(48) == 0) {
                this.tickPrecipitation(level.getBlockRandomPos(i, 0, j, 15));
            }
        }

        profilerfiller.popPush("tickBlocks");
        if (randomTickSpeed > 0) {
            IntSet sectionYs = new IntArraySet();
            for (ServerPlayer player : this.players) {
                if (!player.getChunkTrackingView().contains(chunkpos)) {
                    continue;
                }

                int centerY = Mth.floorDiv(player.getBlockY(), 16);
                for (int sy = centerY - ((WindowedChunk) chunk).windowHalfBelow(); sy <= centerY
                        + ((WindowedChunk) chunk).windowHalfAbove(); sy++) {
                    sectionYs.add(sy);
                }
            }
            for (int sy : sectionYs) {
                LevelChunkSection section = ((WindowedChunk) chunk).windowedAllSections().get(sy);
                if (section == null || !section.isRandomlyTicking()) {
                    continue;
                }

                int k = SectionPos.sectionToBlockCoord(sy);
                for (int l = 0; l < randomTickSpeed; l++) {
                    BlockPos blockpos1 = level.getBlockRandomPos(i, k, j, 15);
                    profilerfiller.push("randomTick");
                    BlockState blockstate = section.getBlockState(
                            blockpos1.getX() - i, blockpos1.getY() - k, blockpos1.getZ() - j);
                    if (blockstate.isRandomlyTicking()) {
                        blockstate.randomTick((ServerLevel) (Object) this, blockpos1, level.getRandom());
                    }

                    FluidState fluidstate = blockstate.getFluidState();
                    if (fluidstate.isRandomlyTicking()) {
                        fluidstate.randomTick((ServerLevel) (Object) this, blockpos1, level.getRandom());
                    }

                    profilerfiller.pop();
                }
            }
        }
        profilerfiller.pop();
    }
}
