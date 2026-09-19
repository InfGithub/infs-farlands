package com.inf.farlands.terrain.noisefiller;

import com.inf.farlands.mixin.noise.NoiseChunkInvoker;
import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.TerrainSystem;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * 维度地形填充器抽象基类，按维度填充一段 section。
 *
 * 与原版 NoiseBasedChunkGenerator.doFill 的差异：
 *   Y 循环裁剪到 [minSection, maxSection]，即按需生成的窗口段，不是全量 cellCountY
 *   每个 X cell 处理完后 swapSlices，全部结束后 stopInterpolation。vanilla 链带 Interpolated
 *   标记的函数依赖 slice 双缓冲推进，缺失会让第 2 个 X cell 起 X 方向错位。纯函数系统不用
 *   slice，无影响。
 *
 * 并发约束：fillCellColumn 更新 chunk 共享的高度图，BitStorage 非线程安全，同 chunk 的 fill
 * 必须串行，由调用方保证，不同 chunk 并行。
 */
public abstract class AbstractNoiseFiller implements NoiseFiller {

    protected final NoiseGeneratorSettings settings;
    protected final Aquifer.FluidPicker fluidPicker;

    protected AbstractNoiseFiller(NoiseGeneratorSettings settings) {
        this.settings = settings;
        this.fluidPicker = createFluidPicker(settings);
    }

    /** 当前填充维度。NoiseChunk 注入按此分派。 */
    protected abstract NoiseFillerContext.TerrainDimension dimension();

    /** 当前维度的地形系统，提供 finalDensity、aquifer、fluidPicker 与 BlockSystem。 */
    protected abstract TerrainSystem terrainSystem();

    /**
     * 维度 biome 查询侧信道设置。DensityFunction.compute 没有 level 引用，需要维度
     * 上下文的系统由子类覆写本钩子。默认空，当前无子类覆写。
     */
    protected void setContext(RandomState randomState, ServerLevel level) {
    }

    /** 与 {@link #setContext} 对称的清理，fill 出口的 finally 调用。 */
    protected void clearContext() {
    }

    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : water;
    }

    /**
     * 构建维度范围 NoiseChunk，用全高 settings，即 SURFACE 阶段的入口。
     * 调用方须已 set NoiseFillerContext。当前 system 全是 void，本方法无调用方。
     */
    public static NoiseChunk createDimensionNoiseChunk(ServerLevel level, ChunkAccess chunk) {
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings genSettings = gen.generatorSettings().value();
        NoiseSettings orig = genSettings.noiseSettings();
        RandomState randomState = level.getChunkSource().randomState();
        Beardifier beardifier = Beardifier.forStructuresInChunk(level.structureManager(), chunk.getPos());
        int cellW = QuartPos.toBlock(orig.noiseSizeHorizontal());
        int cellCountXZ = 16 / cellW;
        return new NoiseChunk(
                cellCountXZ, randomState,
                chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                orig, beardifier, genSettings,
                createFluidPicker(genSettings), Blender.empty());
    }

    /** 填一段 section 的地形，1 段对应 1 个 NoiseChunk。调用方保证同 chunk 串行。 */
    @Override
    public void fill(ServerLevel level, ChunkAccess chunk, int minSectionY, int maxSectionY) {
        RandomState randomState = level.getChunkSource().randomState();
        NoiseFillerContext.set(dimension());
        setContext(randomState, level);
        try {
            NoiseSettings orig = settings.noiseSettings();
            // 网格分派：NoiseSystem 可自定义 noiseSize，null 用维度 settings 默认。
            int[] ns = null;
            if (terrainSystem() instanceof NoiseSystem n) {
                ns = n.noiseSize();
            }
            int noiseH = ns != null ? ns[0] : orig.noiseSizeHorizontal();
            int noiseV = ns != null ? ns[1] : orig.noiseSizeVertical();
            int cellW = QuartPos.toBlock(noiseH);
            int cellH = QuartPos.toBlock(noiseV);

            // 恰好覆盖该段 section 的 NoiseSettings，避免 NoiseSettings.create 的 MAX_Y 校验。
            NoiseSettings customNS = new NoiseSettings(minSectionY * 16, (maxSectionY - minSectionY + 1) * 16, noiseH, noiseV);

            Beardifier beardifier = Beardifier.forStructuresInChunk(level.structureManager(), chunk.getPos());

            int cellCountXZ = 16 / cellW;
            // fluidPicker 按维度系统取，null 用本类默认。
            Aquifer.FluidPicker picker = terrainSystem().createFluidPicker(settings);
            if (picker == null) {
                picker = this.fluidPicker;
            }
            NoiseChunk nc = new NoiseChunk(
                    cellCountXZ, randomState,
                    chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                    customNS, beardifier, settings,
                    picker, Blender.empty());

            int minCellY = Mth.floorDiv(customNS.minY(), cellH);
            int cellCountY = Mth.floorDiv(customNS.height(), cellH);

            doFillRangeWithNoiseChunk(nc, chunk, minSectionY, maxSectionY, minCellY, cellCountY);
        } finally {
            clearContext();
            NoiseFillerContext.clear();
        }
    }

    /** 填充循环，对齐原版 NoiseBasedChunkGenerator.doFill。 */
    protected void doFillRangeWithNoiseChunk(NoiseChunk noisechunk, ChunkAccess chunk,
            int minSection, int maxSection, int minCellY, int cellCountY) {
        Heightmap hmOcean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos cpos = chunk.getPos();
        int baseX = cpos.getMinBlockX();
        int baseZ = cpos.getMinBlockZ();
        Aquifer aquifer = noisechunk.aquifer();
        noisechunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        NoiseChunkInvoker inv = (NoiseChunkInvoker) noisechunk;
        int cw = inv.farlands$cellWidth();
        int ch = inv.farlands$cellHeight();
        int cellsX = 16 / cw;
        int cellsZ = 16 / cw;

        for (int cx = 0; cx < cellsX; cx++) {
            noisechunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsZ; cz++) {
                fillCellColumn(noisechunk, inv, chunk, minSection, maxSection, minCellY, cellCountY,
                        ch, cw, cx, cz, baseX, baseZ, hmOcean, hmSurface, aquifer, mpos, cpos);
            }
            noisechunk.swapSlices();
        }
        noisechunk.stopInterpolation();
    }

    private void fillCellColumn(
            NoiseChunk noisechunk,
            NoiseChunkInvoker inv,
            ChunkAccess chunk,
            int minSection,
            int maxSection,
            int minCellY,
            int cellCountY,
            int ch,
            int cw,
            int cx,
            int cz,
            int baseX,
            int baseZ,
            Heightmap hmOcean,
            Heightmap hmSurface,
            Aquifer aquifer,
            BlockPos.MutableBlockPos mpos,
            ChunkPos cpos) {
        int cyStart = Math.max(0, minSection * 16 / ch - minCellY);
        int cyEnd = Math.min(cellCountY - 1, (maxSection * 16 + 15) / ch - minCellY);
        if (cyStart > cyEnd) {
            return;
        }

        int prevSecIdx = chunk.getSectionsCount() - 1;
        LevelChunkSection section = chunk.getSection(prevSecIdx);
        for (int cy = cyEnd; cy >= cyStart; cy--) {
            noisechunk.selectCellYZ(cy, cz);
            for (int ky = ch - 1; ky >= 0; ky--) {
                int blockY = (minCellY + cy) * ch + ky;
                int ry = blockY & 15;
                int secIdx = chunk.getSectionIndex(blockY);
                if (prevSecIdx != secIdx) {
                    prevSecIdx = secIdx;
                    section = chunk.getSection(secIdx);
                }
                noisechunk.updateForY(blockY, (double) ky / (double) ch);
                for (int kx = 0; kx < cw; kx++) {
                    int blockX = baseX + cx * cw + kx;
                    int rx = blockX & 15;
                    noisechunk.updateForX(blockX, (double) kx / (double) cw);
                    for (int kz = 0; kz < cw; kz++) {
                        int blockZ = baseZ + cz * cw + kz;
                        int rz = blockZ & 15;
                        noisechunk.updateForZ(blockZ, (double) kz / (double) cw);
                        // 块级填充优先，否则走密度链。
                        TerrainSystem sys = terrainSystem();
                        BlockState bs = sys instanceof BlockSystem b ? b.fillBlock(blockX, blockY, blockZ) : null;
                        if (bs == null) {
                            bs = inv.farlands$getInterpolatedState();
                            if (bs == null) {
                                bs = settings.defaultBlock();
                            }
                        }
                        if (bs != Blocks.AIR.defaultBlockState() && !SharedConstants.debugVoidTerrain(cpos)) {
                            section.setBlockState(rx, ry, rz, bs, false);
                            hmOcean.update(rx, blockY, rz, bs);
                            hmSurface.update(rx, blockY, rz, bs);
                            if (aquifer.shouldScheduleFluidUpdate() && !bs.getFluidState().isEmpty()) {
                                mpos.set(blockX, blockY, blockZ);
                                if (chunk instanceof ProtoChunk) {
                                    chunk.markPosForPostprocessing(mpos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
