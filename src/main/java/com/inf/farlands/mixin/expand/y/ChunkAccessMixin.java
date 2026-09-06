package com.inf.farlands.mixin.expand.y;

// import com.inf.farlands.CarvingMaskStorage;
import com.inf.farlands.util.window.EntitySectionWindow;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

import org.slf4j.Logger;

// public abstract class ChunkAccessMixin implements WindowedChunk, CarvingMaskStorage {
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements WindowedChunk {

    @Unique
    private final Map<Integer, LevelChunkSection> allSections = new ConcurrentHashMap<>();

    @Unique
    private volatile LevelChunkSection[] windowSections = new LevelChunkSection[0];

    @Redirect(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 0)
    private void suppressSectionMismatchWarn(Logger logger, String msg, Object a, Object b) {
        // 抑制 section 数组长度不匹配的警告
    }

    @Unique
    private int windowMinY = 4;

    @Unique
    private int lastPacketMinY = Integer.MIN_VALUE;

    @Unique
    private int lastPacketMaxY = Integer.MIN_VALUE;

    @Unique
    private final Map<Integer, Boolean> dirtySections = new ConcurrentHashMap<>();

    @Unique
    private final IntRBTreeSet activeSectionYs = new IntRBTreeSet();

    @Unique
    private PalettedContainerFactory containerFactory;

    // @Unique
    // private final Map<GenerationStep.Carving, CarvingMask> carvingMasks = new
    // EnumMap<>(GenerationStep.Carving.class);

    // @Override
    // public CarvingMask getCarvingMask(GenerationStep.Carving step) {
    // return this.carvingMasks.get(step);
    // }

    // @Override
    // public CarvingMask getOrCreateCarvingMask(GenerationStep.Carving step) {
    // return this.carvingMasks.computeIfAbsent(step,
    // s -> new CarvingMask(this.getHeight(), this.getMinY()));
    // }

    // @Override
    // public void setCarvingMask(GenerationStep.Carving step, CarvingMask mask) {
    // this.carvingMasks.put(step, mask);
    // }

    @Shadow
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    protected LevelChunkSection[] sections;

    @Shadow
    public abstract int getHeight();

    @Shadow
    public abstract int getMinY();

    @Override
    public PalettedContainerFactory containerFactory() {
        return this.containerFactory;
    }

    @Override
    public LevelHeightAccessor levelHeightAccessor() {
        return this.levelHeightAccessor;
    }

    @Override
    public int windowSectionYFromIndex(int idx) {
        if ((Object) this instanceof LevelChunk) {
            return this.windowMinY + idx;
        }
        return this.levelHeightAccessor.getSectionYFromSectionIndex(idx);
    }

    @Override
    public int windowSectionIndexFromY(int y) {
        if ((Object) this instanceof LevelChunk) {
            return y - this.windowMinY;
        }
        return this.levelHeightAccessor.getSectionIndexFromSectionY(y);
    }

    private int _minSection() {
        return this.levelHeightAccessor.getMinSectionY();
    }

    private int _maxSection() {
        return this.levelHeightAccessor.getMaxSectionY();
    }

    private int _maxBuild() {
        return this.levelHeightAccessor.getMaxY();
    }

    private int _sectIdx(int y) {
        return windowSectionIndexFromY(y >> 4);
    }

    @Overwrite
    private static void replaceMissingSections(PalettedContainerFactory containerFactory,
            LevelChunkSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) {
                sections[i] = new LevelChunkSection(containerFactory);
            }
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;getSectionsCount()I"))
    private void initWindowEarly(ChunkPos chunkPos, UpgradeData upgradeData, LevelHeightAccessor lha,
            PalettedContainerFactory containerFactory, long inhabitedTime, LevelChunkSection[] sectionsParam,
            BlendingData blendingData, CallbackInfo ci) {
        if (!((Object) this instanceof ProtoChunk)) {
            this.containerFactory = containerFactory;
            this.windowMinY = -4;
            buildWindow(-4, -4);
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At("RETURN"))
    private void initAllSections(
            ChunkPos chunkPos,
            UpgradeData upgradeData,
            LevelHeightAccessor lha,
            PalettedContainerFactory containerFactory,
            long inhabitedTime,
            LevelChunkSection[] sectionsParam,
            BlendingData blendingData,
            CallbackInfo ci) {
        this.containerFactory = containerFactory;
        if (sectionsParam != null) {
            for (int i = 0; i < sectionsParam.length; i++) {
                if (sectionsParam[i] != null) {
                    int sy = lha.getSectionYFromSectionIndex(i);
                    this.allSections.put(sy, sectionsParam[i]);
                    addActiveSection(sy);
                }
            }
        }
        buildDefaultWindow();
    }

    private void buildDefaultWindow() {
        if ((Object) this instanceof ProtoChunk) {
            buildWindow(4, 4);
        } else {
            buildWindow(_minSection(), _minSection());
        }
    }

    @Override
    public void buildWindow(int sectionYMin, int sectionYMax) {
        if (sectionYMin > sectionYMax) {
            return;
        }
        if (sectionYMin == this.windowMinY && sectionYMax == this.getWindowMaxY()) {
            return;
        }
        int count = sectionYMax - sectionYMin + 1;
        this.windowMinY = sectionYMin;
        LevelChunkSection[] win = new LevelChunkSection[count];
        for (int sy = sectionYMin; sy <= sectionYMax; sy++) {
            int idx = windowSectionIndexFromY(sy);
            LevelChunkSection s = this.getSection(idx);
            win[sy - sectionYMin] = s;
            try {
                LevelChunkSection[] arr = this.sections;
                if (idx >= 0 && idx < arr.length && arr[idx] != s) {
                    arr[idx] = s;
                }
            } catch (Exception ignored) {
            }
        }
        this.windowSections = win;
    }

    @Override
    public int getWindowMinY() {
        return this.windowMinY;
    }

    @Override
    public int getWindowMaxY() {
        return this.windowMinY + this.windowSections.length - 1;
    }

    @Override
    public int lastPacketMinY() {
        return this.lastPacketMinY;
    }

    @Override
    public int lastPacketMaxY() {
        return this.lastPacketMaxY;
    }

    @Override
    public void setLastPacketWindow(int minY, int maxY) {
        this.lastPacketMinY = minY;
        this.lastPacketMaxY = maxY;
    }

    @Override
    public void markSectionDirty(int sectionY) {
        this.dirtySections.put(sectionY, Boolean.TRUE);
    }

    @Override
    public boolean isSectionDirty(int sectionY) {
        return this.dirtySections.containsKey(sectionY);
    }

    @Override
    public void clearSectionDirty(int sectionY) {
        this.dirtySections.remove(sectionY);
    }

    @Override
    public void addActiveSection(int sectionY) {
        synchronized (this.activeSectionYs) {
            this.activeSectionYs.add(sectionY);
        }
    }

    @Override
    public void removeActiveSection(int sectionY) {
        synchronized (this.activeSectionYs) {
            this.activeSectionYs.remove(sectionY);
        }
    }

    @Override
    public void forEachOutsideWindows(int margin, IntConsumer consumer) {
        IntSet collected = new IntOpenHashSet();
        int[] ranges = EntitySectionWindow.ranges();
        synchronized (this.activeSectionYs) {
            for (int i = 0; i < ranges.length; i += 2) {
                int min = ranges[i];
                int max = ranges[i + 1];
                IntIterator it = this.activeSectionYs.headSet(min - margin).iterator();
                while (it.hasNext()) {
                    collected.add(it.nextInt());
                }
                it = this.activeSectionYs.tailSet(max + margin + 1).iterator();
                while (it.hasNext()) {
                    collected.add(it.nextInt());
                }
            }
        }
        collected.forEach(consumer);
    }

    @Override
    public Map<Integer, LevelChunkSection> windowedAllSections() {
        return this.allSections;
    }

    @Overwrite
    public LevelChunkSection[] getSections() {
        return this.windowSections;
    }

    @Overwrite
    public LevelChunkSection getSection(int index) {
        int sy = windowSectionYFromIndex(index);
        LevelChunkSection s = this.allSections.get(sy);
        if (s == null) {
            s = new LevelChunkSection(this.containerFactory);
            this.allSections.put(sy, s);
            addActiveSection(sy);
        }
        try {
            LevelChunkSection[] arr = this.sections;
            if (index >= 0 && index < arr.length) {
                arr[index] = s;
            }
            if (index >= 0 && index < this.windowSections.length) {
                this.windowSections[index] = s;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    @Overwrite
    public int getHighestFilledSectionIndex() {
        int max = -1;
        if ((Object) this instanceof ProtoChunk) {
            for (Integer sy : this.allSections.keySet()) {
                if (sy > max)
                    max = sy;
            }
            return max == -1 ? -1 : windowSectionIndexFromY(max);
        }
        int sectionsCount = this.getSections().length;
        for (Integer sy : this.allSections.keySet()) {
            int idx = windowSectionIndexFromY(sy);
            if (idx >= 0 && idx < sectionsCount && idx > max) {
                max = idx;
            }
        }
        return max;
    }

    @Overwrite
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        try {
            int j = _sectIdx(QuartPos.toBlock(Mth.clamp(y,
                    QuartPos.fromBlock(this.levelHeightAccessor.getMinY()),
                    QuartPos.fromBlock(this.levelHeightAccessor.getMinY())
                            + QuartPos.fromBlock(this.levelHeightAccessor.getHeight()) - 1)));
            LevelChunkSection s = this.getSection(j);
            Holder<Biome> result;
            if (s != null) {
                result = s.getNoiseBiome(x & 3, y & 3, z & 3);
            } else {
                result = this.containerFactory.defaultBiome();
            }
            return result;
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Getting biome");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Biome being got");
            crashreportcategory.setDetail("Location",
                    () -> CrashReportCategory.formatLocation(((ChunkAccess) (Object) this), x, y, z));
            throw new ReportedException(crashreport);
        }
    }

    @Overwrite
    public boolean isYSpaceEmpty(int startY, int endY) {
        if (startY < this.levelHeightAccessor.getMinY()) {
            startY = this.levelHeightAccessor.getMinY();
        }
        if (endY >= _maxBuild()) {
            endY = _maxBuild() - 1;
        }
        for (int i = startY; i <= endY; i += 16) {
            LevelChunkSection s = this.getSection(_sectIdx(i));
            if (s != null && !s.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }

    @Overwrite
    public void findBlocks(
            Predicate<BlockState> predicate,
            BiConsumer<BlockPos, BlockState> consumer) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int i = _minSection(); i <= _maxSection(); i++) {
            LevelChunkSection s = this.getSection(windowSectionIndexFromY(i));
            if (s == null) {
                continue;
            }
            if (s.maybeHas(predicate)) {
                BlockPos origin = SectionPos.of(self.getPos(), i).origin();
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++) {
                            BlockState state = s.getBlockState(x, y, z);
                            if (predicate.test(state)) {
                                consumer.accept(mpos.setWithOffset(origin, x, y, z), state);
                            }
                        }
            }
        }
    }

    @Overwrite
    public void markPosForPostprocessing(BlockPos pos) {
        // LevelChunk 不支持后处理；静默忽略。
    }

    @Overwrite
    public void fillBiomesFromNoise(BiomeResolver resolver, Climate.Sampler sampler) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        ChunkPos cpos = self.getPos();
        int i = QuartPos.fromBlock(cpos.getMinBlockX());
        int j = QuartPos.fromBlock(cpos.getMinBlockZ());
        LevelHeightAccessor lha = this.levelHeightAccessor;
        for (int k = lha.getMinSectionY(); k < lha.getMaxSectionY(); k++) {
            LevelChunkSection s = this.getSection(windowSectionIndexFromY(k));
            if (s == null) {
                continue;
            }
            int l = QuartPos.fromSection(k);
            s.fillBiomesFromNoise(resolver, sampler, i, l, j);
        }
    }
}