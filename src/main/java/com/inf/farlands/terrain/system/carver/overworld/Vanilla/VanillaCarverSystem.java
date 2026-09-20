package com.inf.farlands.terrain.system.carver.overworld.Vanilla;

import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.CarvingMaskStorage;
import com.inf.farlands.terrain.terrainFiller.AbstractTerrainFiller;
import com.inf.farlands.terrain.terrainFiller.TerrainFillerContext;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;

/**
 * vanilla 主世界雕刻系统：biome json 的 carver 列表，CAVE/CAVE_EXTRA_UNDERGROUND/CANYON，
 * 加 WorldCarver 体系，目标 chunk 为中心 ±8 chunk 的起点网格。
 *
 * 移植自 vanilla NoiseBasedChunkGenerator.applyCarvers 的 1.21.1 形态：
 * - 以目标 chunk 为中心遍历 17×17 个起点，289 次调用。每个起点用 biomeSource 查 biome，
 *   再取该 biome 的 carver 列表，不依赖起点 chunk 的数据，起点未生成也能查询
 * - setLargeFeatureSeed(世界seed + carver序号, 起点cx, 起点cz) 做确定性随机，经 isStartChunk
 *   概率过滤，再 ConfiguredWorldCarver.carve。carvingMask 去重，只雕目标 chunk
 * - CarvingContext 用维度全高 NoiseChunk，其 aquifer 覆盖 carver 带，同 SURFACE 模式
 *
 * Y 有界：carver 起点 Y 由 biome json 配置的 HeightProvider 给出，锚定在维度带内，极端 Y
 * 天然不会被雕。carve 只替换 config.replaceable 即 stone，替换成什么由 aquifer.computeSubstance
 * 决定，空气或水，y ≤ lavaLevel 时填岩浆。
 *
 * 无状态：全部局部构造 CarvingContext/NoiseChunk/WorldgenRandom，单例共享安全。
 */
public final class VanillaCarverSystem implements CarverSystem {

    /** 起点网格半径，vanilla applyCarvers 硬编码 8。 */
    private static final int GRID_RADIUS = 8;

    @Override
    public void applyCarvers(ServerLevel level, ChunkAccess chunk) {
        RandomState random = level.getChunkSource().randomState();
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        // NoiseChunkMixin 的 @Redirect 按维度分派 finalDensity 与 aquifer，carver 只用 aquifer，
        // 但维度全高 NoiseChunk 的构造会走那两处 @Redirect
        TerrainFillerContext.set(TerrainFillerContext.TerrainDimension.OVERWORLD);
        try {
            // 维度全高 NoiseChunk，其 aquifer 网格覆盖 carver 带，fill 的窗口段 NoiseChunk 不覆盖
            NoiseChunk nc = chunk.getOrCreateNoiseChunk(
                    p -> AbstractTerrainFiller.createDimensionNoiseChunk(level, chunk));
            Aquifer aquifer = nc.aquifer();
            CarvingContext carvingContext = new CarvingContext(
                    gen, level.registryAccess(), chunk.getHeightAccessorForGeneration(),
                    nc, random, settings.surfaceRule());
            CarvingMask carvingMask = ((CarvingMaskStorage) chunk).getOrCreateCarvingMask();

            // biomeAccessor 直接查 biomeSource，供 carveBlock 的表面 dirt 换草皮 topMaterial 用，
            // 不依赖 chunk section biome，起点可能尚未生成
            Function<BlockPos, Holder<Biome>> biomeAccessor = pos -> gen.getBiomeSource()
                    .getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                            QuartPos.fromBlock(pos.getZ()), random.sampler());

            ChunkPos target = chunk.getPos();
            WorldgenRandom worldgenrandom = new WorldgenRandom(
                    new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
            for (int j = -GRID_RADIUS; j <= GRID_RADIUS; j++) {
                for (int k = -GRID_RADIUS; k <= GRID_RADIUS; k++) {
                    ChunkPos start = new ChunkPos(target.x() + j, target.z() + k);
                    // quart 用 fromSection，等于 start*4，而非 fromBlock(getMinBlockX())：
                    // getMinBlockX 被本 port 的饱和 @Overwrite 覆盖，边界 chunk 会错位
                    Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(
                            QuartPos.fromSection(start.x()), 0, QuartPos.fromSection(start.z()),
                            random.sampler());
                    // 26.1.2 把 ChunkGenerator.getBiomeGenerationSettings 标了 @Deprecated，而它对
                    // NoiseBasedChunkGenerator 的实现就是 biome.value().getGenerationSettings()
                    BiomeGenerationSettings biomeGen = biome.value().getGenerationSettings();
                    int l = 0;
                    for (Holder<ConfiguredWorldCarver<?>> holder : biomeGen.getCarvers()) {
                        ConfiguredWorldCarver<?> carver = holder.value();
                        worldgenrandom.setLargeFeatureSeed(level.getSeed() + l, start.x(), start.z());
                        if (carver.isStartChunk(worldgenrandom)) {
                            carver.carve(carvingContext, chunk, biomeAccessor, worldgenrandom,
                                    aquifer, start, carvingMask);
                        }
                        l++;
                    }
                }
            }
        } finally {
            TerrainFillerContext.clear();
        }
    }
}
