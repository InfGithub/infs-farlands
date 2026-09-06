package com.inf.farlands.terrain.noisefiller;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * NoiseChunk 逐格填充逻辑。
 *
 * fabric 尚无完整 terrain 管线，本类为静态方法形态；未来演进为抽象 filler。
 */
public final class NoiseChunkFiller {

    private NoiseChunkFiller() {
    }

    private static final Method M_CELL_WIDTH;
    private static final Method M_CELL_HEIGHT;
    private static final Method M_SELECT_CELL_YZ;
    private static final Method M_ADVANCE_CELL_X;
    private static final Method M_INIT_FIRST_CELL_X;
    private static final Method M_UPDATE_FOR_Y;
    private static final Method M_UPDATE_FOR_X;
    private static final Method M_UPDATE_FOR_Z;
    private static final Method M_GET_INTERP_STATE;
    private static final Method M_AQUIFER;

    static {
        try {
            M_CELL_WIDTH = NoiseChunk.class.getDeclaredMethod("cellWidth");
            M_CELL_WIDTH.setAccessible(true);
            M_CELL_HEIGHT = NoiseChunk.class.getDeclaredMethod("cellHeight");
            M_CELL_HEIGHT.setAccessible(true);
            M_SELECT_CELL_YZ = NoiseChunk.class.getDeclaredMethod("selectCellYZ", int.class, int.class);
            M_SELECT_CELL_YZ.setAccessible(true);
            M_ADVANCE_CELL_X = NoiseChunk.class.getDeclaredMethod("advanceCellX", int.class);
            M_ADVANCE_CELL_X.setAccessible(true);
            M_INIT_FIRST_CELL_X = NoiseChunk.class.getDeclaredMethod("initializeForFirstCellX");
            M_INIT_FIRST_CELL_X.setAccessible(true);
            M_UPDATE_FOR_Y = NoiseChunk.class.getDeclaredMethod("updateForY", int.class, double.class);
            M_UPDATE_FOR_Y.setAccessible(true);
            M_UPDATE_FOR_X = NoiseChunk.class.getDeclaredMethod("updateForX", int.class, double.class);
            M_UPDATE_FOR_X.setAccessible(true);
            M_UPDATE_FOR_Z = NoiseChunk.class.getDeclaredMethod("updateForZ", int.class, double.class);
            M_UPDATE_FOR_Z.setAccessible(true);
            M_GET_INTERP_STATE = NoiseChunk.class.getDeclaredMethod("getInterpolatedState");
            M_GET_INTERP_STATE.setAccessible(true);
            M_AQUIFER = NoiseChunk.class.getDeclaredMethod("aquifer");
            M_AQUIFER.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 窗口滑入 section 的按需填充。 */
    public static void fillWindowSections(ServerLevel level, ChunkAccess chunk, int minSection, int maxSection,
            NoiseGeneratorSettings settings, Supplier<Aquifer.FluidPicker> globalFluidPicker) {
        NoiseSettings orig = settings.noiseSettings();
        int noiseH = orig.noiseSizeHorizontal();
        int noiseV = orig.noiseSizeVertical();
        int cellW = QuartPos.toBlock(noiseH);
        int cellH = QuartPos.toBlock(noiseV);

        int minBlockY = minSection * 16;
        int maxBlockY = (maxSection + 1) * 16 - 1;
        int height = maxBlockY - minBlockY + 1;
        if (height % 16 != 0)
            height = ((height + 15) / 16) * 16;

        NoiseSettings customNS = new NoiseSettings(minBlockY, height, noiseH, noiseV);

        RandomState randomState = level.getChunkSource().randomState();
        Beardifier beardifier = Beardifier.forStructuresInChunk(level.structureManager(), chunk.getPos());

        int cellCountXZ = 16 / cellW;
        NoiseChunk nc = new NoiseChunk(
                cellCountXZ, randomState,
                chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                customNS, beardifier, settings,
                globalFluidPicker.get(), Blender.empty());

        int minCellY = Mth.floorDiv(customNS.minY(), cellH);
        int cellCountY = Mth.floorDiv(customNS.height(), cellH);

        doFillRangeWithNoiseChunk(nc, chunk, settings, minSection, maxSection, minCellY, cellCountY);
    }

    /** 初始全量填充。 */
    public static ChunkAccess doFillRange(Blender blender, StructureManager structureManager, RandomState random,
            ChunkAccess chunk, int minSection, int maxSection,
            NoiseGeneratorSettings settings, Supplier<Aquifer.FluidPicker> globalFluidPicker) {
        NoiseChunk nc = chunk.getOrCreateNoiseChunk(p -> NoiseChunk.forChunk(
                p, random,
                Beardifier.forStructuresInChunk(structureManager, p.getPos()),
                settings,
                globalFluidPicker.get(),
                blender));
        NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int cellH = ns.getCellHeight();
        int minCellY = Mth.floorDiv(ns.minY(), cellH);
        int cellCountY = Mth.floorDiv(ns.height(), cellH);
        return doFillRangeWithNoiseChunk(nc, chunk, settings, minSection, maxSection, minCellY, cellCountY);
    }

    private static ChunkAccess doFillRangeWithNoiseChunk(NoiseChunk noisechunk, ChunkAccess chunk,
            NoiseGeneratorSettings settings,
            int minSection, int maxSection, int minCellY, int cellCountY) {
        Heightmap hmOcean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos cpos = chunk.getPos();
        int baseX = cpos.getMinBlockX();
        int baseZ = cpos.getMinBlockZ();
        Aquifer aquifer = (Aquifer) invoke(M_AQUIFER, noisechunk);
        invoke(M_INIT_FIRST_CELL_X, noisechunk);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int cw = (int) invoke(M_CELL_WIDTH, noisechunk);
        int ch = (int) invoke(M_CELL_HEIGHT, noisechunk);
        int cellsX = 16 / cw;
        int cellsZ = 16 / cw;

        int totalSections = chunk.getSectionsCount();
        for (int i = 0; i < totalSections; i++) {
            chunk.getSection(i);
        }

        for (int cx = 0; cx < cellsX; cx++) {
            invoke(M_ADVANCE_CELL_X, noisechunk, cx);
            for (int cz = 0; cz < cellsZ; cz++) {
                fillCellColumn(noisechunk, chunk, settings, minSection, maxSection, minCellY, cellCountY,
                        ch, cw, cx, cz, baseX, baseZ, hmOcean, hmSurface, aquifer, mpos, cpos);
            }
        }
        return chunk;
    }

    private static void fillCellColumn(
            NoiseChunk noisechunk,
            ChunkAccess chunk,
            NoiseGeneratorSettings settings,
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
            invoke(M_SELECT_CELL_YZ, noisechunk, cy, cz);
            for (int ky = ch - 1; ky >= 0; ky--) {
                int blockY = (minCellY + cy) * ch + ky;
                int ry = blockY & 15;
                int secIdx = chunk.getSectionIndex(blockY);
                if (prevSecIdx != secIdx) {
                    prevSecIdx = secIdx;
                    section = chunk.getSection(secIdx);
                }
                invoke(M_UPDATE_FOR_Y, noisechunk, blockY, (double) ky / (double) ch);
                for (int kx = 0; kx < cw; kx++) {
                    int blockX = baseX + cx * cw + kx;
                    int rx = blockX & 15;
                    invoke(M_UPDATE_FOR_X, noisechunk, blockX, (double) kx / (double) cw);
                    for (int kz = 0; kz < cw; kz++) {
                        int blockZ = baseZ + cz * cw + kz;
                        int rz = blockZ & 15;
                        invoke(M_UPDATE_FOR_Z, noisechunk, blockZ, (double) kz / (double) cw);
                        BlockState bs = (BlockState) invoke(M_GET_INTERP_STATE, noisechunk);
                        if (bs == null) {
                            bs = settings.defaultBlock();
                        }
                        bs = applyDebugPreliminarySurfaceLevel(noisechunk, settings.seaLevel(),
                                blockX, blockY, blockZ, bs);
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

    private static BlockState applyDebugPreliminarySurfaceLevel(NoiseChunk noiseChunk, int seaLevel,
            int x, int y, int z, BlockState state) {
        if (SharedConstants.DEBUG_AQUIFERS && z >= 0 && z % 4 == 0) {
            int preliminarySurfaceLevel = noiseChunk.preliminarySurfaceLevel(x, z);
            int adjustedSurfaceLevel = preliminarySurfaceLevel + 8;
            if (y == adjustedSurfaceLevel) {
                state = adjustedSurfaceLevel < seaLevel
                        ? Blocks.SLIME_BLOCK.defaultBlockState()
                        : Blocks.HONEY_BLOCK.defaultBlockState();
            }
        }
        return state;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
