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
            // 读路径不物化段：缺段即全空气。走 getSection 会把任意 Y 的读取变成段的来源，
            // heightmap 的 2048 格下扫与光探针的 512 格下扫因此各能造出上百个空占位段。
            LevelChunkSection s = ((WindowedChunk) ca).windowedAllSections().get(y >> 4);
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
            // 与 getBlockState 同规：读不建段，缺段即无流体。
            LevelChunkSection s = ((WindowedChunk) ca).windowedAllSections().get(y >> 4);
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

        int sectionCount = buffer.readVarInt();
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = buffer.readVarInt();
            // 不可变切换：新建 section 整体替换。PalettedContainer.read 的 createOrReuseData 会复用
            // 现有 Data 原地改 palette，与渲染编译线程并发读会读到 palette 中间状态。
            LevelChunkSection s = new LevelChunkSection(wc.containerFactory());
            s.read(buffer);
            all.put(sectionY, s);
            ca.getSection(ca.getSectionIndexFromSectionY(sectionY)); // 数组同步：get 内部执行 arr[idx]=s
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

    /**
     * biomes 包按绝对 sectionY 落位，与写侧的段数加 sectionY 格式配套。
     *
     * 不能按 getSections() 的顺序解：那是窗口视图，客户端窗口跟着相机滑动，与服务端发送时的
     * 段集不一致。落位走 getSection，越出窗口数组的索引也会写进 allSections 的正确 sy。
     */
    @Overwrite
    public void replaceBiomes(FriendlyByteBuf buffer) {
        ChunkAccess ca = (ChunkAccess) (Object) this;
        int sectionCount = buffer.readVarInt();
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = buffer.readVarInt();
            LevelChunkSection s = ca.getSection(ca.getSectionIndexFromSectionY(sectionY));
            if (s != null) {
                s.readBiomes(buffer);
            }
        }
    }
}