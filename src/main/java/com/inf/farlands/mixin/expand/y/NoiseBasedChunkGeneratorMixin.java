package com.inf.farlands.mixin.expand.y;

import java.util.function.Supplier;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.inf.farlands.terrain.noisefiller.NoiseChunkFiller;

import net.minecraft.core.Holder;

/**
 * NoiseBasedChunkGenerator 的窗口化 fill 入口。逐格填充逻辑在
 * {@link NoiseChunkFiller}。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

        @Shadow
        private Holder<NoiseGeneratorSettings> settings;

        @Shadow
        private Supplier<Aquifer.FluidPicker> globalFluidPicker;

        @Overwrite
        private ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState random,
                        ChunkAccess chunk, int minCellY, int cellCountY) {
                NoiseSettings ns = this.settings.value().noiseSettings()
                                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
                int cellH = ns.getCellHeight();
                int minSy = SectionPos.blockToSectionCoord(minCellY * cellH);
                int maxSy = SectionPos.blockToSectionCoord((minCellY + cellCountY) * cellH - 1);
                return NoiseChunkFiller.doFillRange(blender, structureManager, random, chunk, minSy, maxSy,
                                this.settings.value(), this.globalFluidPicker);
        }

        // 预留入口，当前无调用点

        @Unique
        public void fillWindowSections(ServerLevel level, ChunkAccess chunk, int minSection, int maxSection) {
                NoiseChunkFiller.fillWindowSections(level, chunk, minSection, maxSection,
                                this.settings.value(), this.globalFluidPicker);
        }
}
