package com.inf.farlands.mixin.noise;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * NoiseChunk 的成员访问器，供 AbstractNoiseFiller 与 NoiseChunkFiller 直调，避免反射。
 * 26.1.2 里 cellWidth、cellHeight、getInterpolatedState 是 protected，其余 cell 驱动方法是 public。
 */
@Mixin(NoiseChunk.class)
public interface NoiseChunkInvoker {

    @Invoker("cellWidth")
    int farlands$cellWidth();

    @Invoker("cellHeight")
    int farlands$cellHeight();

    @Invoker("selectCellYZ")
    void farlands$selectCellYZ(int y, int z);

    @Invoker("advanceCellX")
    void farlands$advanceCellX(int increment);

    @Invoker("initializeForFirstCellX")
    void farlands$initializeForFirstCellX();

    @Invoker("updateForY")
    void farlands$updateForY(int cellEndBlockY, double y);

    @Invoker("updateForX")
    void farlands$updateForX(int cellEndBlockX, double x);

    @Invoker("updateForZ")
    void farlands$updateForZ(int cellEndBlockZ, double z);

    @Invoker("getInterpolatedState")
    BlockState farlands$getInterpolatedState();

    @Invoker("aquifer")
    Aquifer farlands$aquifer();
}
