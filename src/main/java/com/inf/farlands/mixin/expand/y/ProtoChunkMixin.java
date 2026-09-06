package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;


@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMixin {

    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        ChunkAccess ca = (ChunkAccess) (Object) this;
        if (!WorldBounds.inBuildHeight(y)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunkSection s = ca.getSection(((WindowedChunk) ca).levelHeightAccessor().getSectionIndex(y));
        return (s == null || s.hasOnlyAir())
                ? Blocks.AIR.defaultBlockState()
                : s.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }

    @Overwrite
    public FluidState getFluidState(BlockPos pos) {
        int y = pos.getY();
        ChunkAccess ca = (ChunkAccess) (Object) this;
        if (!WorldBounds.inBuildHeight(y)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        LevelChunkSection s = ca.getSection(((WindowedChunk) ca).levelHeightAccessor().getSectionIndex(y));
        return (s == null || s.hasOnlyAir())
                ? Fluids.EMPTY.defaultFluidState()
                : s.getFluidState(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }
}