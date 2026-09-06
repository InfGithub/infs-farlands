package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelChunk.class);

    @Redirect(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ProtoChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] redirectGetSections(ProtoChunk chunk) {
        LevelHeightAccessor lha = ((WindowedChunk) chunk).levelHeightAccessor();
        Map<Integer, LevelChunkSection> all = ((WindowedChunk) chunk).windowedAllSections();
        int min = lha.getMinSectionY();
        int max = lha.getMaxSectionY();
        LevelChunkSection[] arr = new LevelChunkSection[max - min + 1];
        for (int i = min; i <= max; i++) {
            arr[i - min] = all.get(i);
        }
        return arr;
    }

    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        try {
            ChunkAccess ca = (ChunkAccess) (Object) this;
            LevelChunkSection s = ca.getSection(((LevelHeightAccessor) (Object) this).getSectionIndex(y));
            if (s != null && !s.hasOnlyAir()) {
                return s.getBlockState(x & 15, y & 15, z & 15);
            }
            return Blocks.AIR.defaultBlockState();
        } catch (Throwable t) {
            CrashReport cr = CrashReport.forThrowable(t, "Getting block state");
            CrashReportCategory cat = cr.addCategory("Block being got");
            cat.setDetail("Location", () -> CrashReportCategory.formatLocation(((LevelChunk) (Object) this), x, y, z));
            throw new ReportedException(cr);
        }
    }

    @Overwrite
    public FluidState getFluidState(int x, int y, int z) {
        try {
            ChunkAccess ca = (ChunkAccess) (Object) this;
            LevelChunkSection s = ca.getSection(((LevelHeightAccessor) (Object) this).getSectionIndex(y));
            if (s != null && !s.hasOnlyAir()) {
                return s.getFluidState(x & 15, y & 15, z & 15);
            }
            return Fluids.EMPTY.defaultFluidState();
        } catch (Throwable t) {
            CrashReport cr = CrashReport.forThrowable(t, "Getting fluid state");
            CrashReportCategory cat = cr.addCategory("Block being got");
            cat.setDetail("Location", () -> CrashReportCategory.formatLocation(((LevelChunk) (Object) this), x, y, z));
            throw new ReportedException(cr);
        }
    }

    @Overwrite
    public void replaceWithPacketData(
            FriendlyByteBuf buffer,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> outputTagConsumer) {
        LevelChunk self = (LevelChunk) (Object) this;
        self.clearAllBlockEntities();
        ChunkAccess ca = (ChunkAccess) (Object) this;
        WindowedChunk wc = (WindowedChunk) this;
        Map<Integer, LevelChunkSection> all = wc.windowedAllSections();

        LevelChunkSection[] window = ca.getSections();
        int windowMinY = wc.getWindowMinY();
        for (int i = 0; i < window.length; i++) {
            LevelChunkSection s = window[i];
            if (s == null) {
                s = new LevelChunkSection(wc.containerFactory());
                window[i] = s;
                all.put(windowMinY + i, s);
            }
            s.read(buffer);
        }

        heightmaps.forEach(ca::setHeightmap);
        ca.initializeLightSources();

        ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(ca.problemPath(), LOGGER);
        try {
            outputTagConsumer.accept((pos, beType, updateTag) -> {
                BlockEntity be = self.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                if (be != null && updateTag != null && be.getType() == beType) {
                    be.loadWithComponents(TagValueInput.create(reporter.forChild(be.problemPath()),
                            self.getLevel().registryAccess(), updateTag));
                }
            });
        } catch (Throwable t) {
            try {
                reporter.close();
            } catch (Throwable t2) {
                t.addSuppressed(t2);
            }
            throw t;
        }
        reporter.close();
    }

    @Overwrite
    public void replaceBiomes(FriendlyByteBuf buffer) {
        ChunkAccess ca = (ChunkAccess) (Object) this;
        for (LevelChunkSection s : ca.getSections()) {
            if (s != null) {
                s.readBiomes(buffer);
            }
        }
    }
}