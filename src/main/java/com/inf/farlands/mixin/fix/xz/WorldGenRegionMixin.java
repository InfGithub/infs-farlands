package com.inf.farlands.mixin.fix.xz;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 世界生成区域的取 chunk 与难度查询不再抛异常。
 *
 * <p>
 * 窗口能让生成器请求到生成区域之外的 chunk。vanilla 的 {@code getChunk} 在这种时候构造
 * CrashReport 抛 ReportedException，{@code getCurrentDifficultyAt} 在
 * {@code hasChunk} 为假时抛 RuntimeException，而本 mod 的生成按窗口段推进，越界请求属于正常路径。
 * 两处都改成降级：取不到就返回中心 chunk，难度按 level 现算。
 *
 * <p>
 * {@code StaticCache2D.get} 对越界坐标抛 IllegalArgumentException，所以取 holder
 * 时先试缓存，失败再问
 * 可见 chunk 表；后者在 26.1.2 是 protected，走 {@link ChunkMapInvoker}。
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Shadow
    @Final
    private ChunkAccess center;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private StaticCache2D<GenerationChunkHolder> cache;

    @Shadow
    @Final
    private ChunkStep generatingStep;

    private GenerationChunkHolder farlands$getHolder(int x, int z) {
        try {
            return this.cache.get(x, z);
        } catch (Exception e) {
            ServerChunkCache chunkSource = (ServerChunkCache) this.level.getChunkSource();
            return ((ChunkMapInvoker) chunkSource.chunkMap).farlands$getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        }
    }

    @Overwrite
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus, boolean loadOrGenerate) {
        int distance = this.center.getPos().getChessboardDistance(chunkX, chunkZ);
        ChunkStatus maxAllowedStatus = distance >= this.generatingStep.directDependencies().size()
                ? null
                : this.generatingStep.directDependencies().get(distance);
        if (maxAllowedStatus == null) {
            return this.center;
        }
        GenerationChunkHolder holder = this.farlands$getHolder(chunkX, chunkZ);
        if (holder != null && targetStatus.isOrBefore(maxAllowedStatus)) {
            ChunkAccess chunk = holder.getChunkIfPresentUnchecked(maxAllowedStatus);
            if (chunk != null) {
                return chunk;
            }
        }
        return this.center;
    }

    @Overwrite
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(this.level.getDifficulty(), this.level.getOverworldClockTime(), 0L,
                this.level.getMoonBrightness(pos));
    }
}
