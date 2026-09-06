package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.core.Registry;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Shadow
    private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    @Shadow
    private BiomeSource biomeSource;

    @Shadow
    private Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;

    @Overwrite
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        ChunkPos chunkpos = chunk.getPos();
        if (!SharedConstants.debugVoidTerrain(chunkpos)) {
            SectionPos sectionpos = SectionPos.of(chunkpos, level.getMinSectionY());
            BlockPos blockpos = sectionpos.origin();
            Registry<Structure> registry = level.registryAccess()
                    .lookupOrThrow(Registries.STRUCTURE);
            Map<Integer, List<Structure>> map = registry.stream()
                    .collect(Collectors.groupingBy(s -> s.step().ordinal()));
            List<FeatureSorter.StepFeatureData> list = this.featuresPerStep.get();
            WorldgenRandom worldgenrandom = new WorldgenRandom(
                    new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
            long i = worldgenrandom.setDecorationSeed(level.getSeed(), blockpos.getX(), blockpos.getZ());
            ObjectArraySet<Holder<Biome>> set = new ObjectArraySet<>();
            ChunkPos.rangeClosed(sectionpos.chunk(), 1).forEach(p -> {
                ChunkAccess ca = level.getChunk(p.x(), p.z());
                for (LevelChunkSection s : getAllSections(ca)) {
                    if (s == null) {
                        continue;
                    }
                    s.getBiomes().getAll(set::add);
                }
            });
            set.retainAll(this.biomeSource.possibleBiomes());
            int j = list.size();

            try {
                Registry<PlacedFeature> registry1 = level.registryAccess()
                        .lookupOrThrow(Registries.PLACED_FEATURE);
                int i1 = Math.max(GenerationStep.Decoration.values().length, j);

                for (int k = 0; k < i1; k++) {
                    if (!structureManager.shouldGenerateStructures()) {
                        continue;
                    }
                    int l = 0;
                    for (Structure structure : map.getOrDefault(k, Collections.emptyList())) {
                        worldgenrandom.setFeatureSeed(i, l, k);
                        Supplier<String> supplier = () -> registry.getResourceKey(structure).map(Object::toString)
                                .orElseGet(structure::toString);
                        try {
                            level.setCurrentlyGenerating(supplier);
                            structureManager
                                    .startsForStructure(sectionpos, structure)
                                    .forEach(s1 -> s1.placeInChunk(
                                            level,
                                            structureManager,
                                            (ChunkGenerator) (Object) this,
                                            worldgenrandom,
                                            getWritableArea(chunk),
                                            chunkpos));
                        } catch (Exception exception) {
                            CrashReport crashreport1 = CrashReport.forThrowable(exception, "Feature placement");
                            crashreport1.addCategory("Feature").setDetail("Description", supplier::get);
                            throw new ReportedException(crashreport1);
                        }
                        l++;
                    }

                    if (k >= j) {
                        continue;
                    }

                    IntSet intset = new IntArraySet();
                    for (Holder<Biome> holder : set) {
                        List<HolderSet<PlacedFeature>> list1 = this.generationSettingsGetter.apply(holder)
                                .features();
                        if (k < list1.size()) {
                            HolderSet<PlacedFeature> holderset = list1.get(k);
                            FeatureSorter.StepFeatureData d = list.get(k);
                            holderset.stream().map(Holder::value)
                                    .forEach(f -> intset.add(d.indexMapping().applyAsInt(f)));
                        }
                    }

                    int j1 = intset.size();
                    int[] aint = intset.toIntArray();
                    Arrays.sort(aint);
                    FeatureSorter.StepFeatureData featuresorter$stepfeaturedata = list.get(k);
                    for (int k1 = 0; k1 < j1; k1++) {
                        int l1 = aint[k1];
                        PlacedFeature placedfeature = featuresorter$stepfeaturedata.features().get(l1);
                        Supplier<String> supplier1 = () -> registry1.getResourceKey(placedfeature)
                                .map(Object::toString).orElseGet(placedfeature::toString);
                        worldgenrandom.setFeatureSeed(i, l1, k);
                        try {
                            level.setCurrentlyGenerating(supplier1);
                            placedfeature.placeWithBiomeCheck(level, (ChunkGenerator) (Object) this, worldgenrandom,
                                    blockpos);
                        } catch (Exception exception1) {
                            CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                            crashreport2.addCategory("Feature").setDetail("Description", supplier1::get);
                            throw new ReportedException(crashreport2);
                        }
                    }
                }
                level.setCurrentlyGenerating(null);
            } catch (Exception exception2) {
                CrashReport crashreport = CrashReport.forThrowable(exception2, "Biome decoration");
                crashreport.addCategory("Generation").setDetail("CenterX", chunkpos.x())
                        .setDetail("CenterZ", chunkpos.z())
                        .setDetail("Decoration Seed", i);
                throw new ReportedException(crashreport);
            }
        }
    }

    @Shadow
    private static BoundingBox getWritableArea(ChunkAccess chunk) {
        throw new AbstractMethodError();
    }

    private static Iterable<LevelChunkSection> getAllSections(ChunkAccess ca) {
        Map<Integer, LevelChunkSection> all = ((WindowedChunk) ca).windowedAllSections();
        if (all != null && !all.isEmpty())
            return all.values();
        return java.util.Arrays.asList(ca.getSections());
    }
}