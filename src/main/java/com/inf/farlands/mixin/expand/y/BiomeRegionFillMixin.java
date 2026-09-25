package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.terrain.biomeFiller.BiomeFiller;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.datafixers.util.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * /fillbiome 按请求区域的段范围逐段填，不受维度段范围的限高。
 *
 * vanilla 对每个 chunk 调一次 ChunkAccess.fillBiomesFromNoise，而本 port 那个覆写按关卡的段
 * 范围循环，只有 y 在 -64 到 303 之间会被写，其余静默跳过。这里改成按 region 的段范围逐段
 * 调用段级 fillBiomesFromNoise，段不存在由 getSection 按需建出。
 *
 * 两处与 vanilla 不同。其一，XZ 的 quart 基准用 QuartPos.fromSection：本 port 的 getMinBlockX
 * 饱和到上限减 15，vanilla 的 QuartPos.fromBlock 会差 3 个 quart，即 12 格布局错位。其二，填
 * 之前先按群系系统 seed 区域内的段，replace 形态的当前群系判据才落在本该有的群系上；
 * BiomeFiller 按 SectionStage 门控，已经填过的段不会被重置，连续两次命令不互相覆盖。
 *
 * 单轴溢出的判据仍由 fix/xz 那份 mixin 的 HEAD 注入负责，它在这一遍之后应用，落在本覆写体
 * 的最前面，体积算式因此不必再自己防回绕。
 */
@Mixin(FillBiomeCommand.class)
public abstract class BiomeRegionFillMixin {

    @Shadow
    @Final
    private static SimpleCommandExceptionType ERROR_NOT_LOADED;

    @Shadow
    @Final
    private static Dynamic2CommandExceptionType ERROR_VOLUME_TOO_LARGE;

    @Shadow
    private static BlockPos quantize(BlockPos block) {
        throw new AbstractMethodError();
    }

    @Shadow
    private static BiomeResolver makeResolver(MutableInt count, ChunkAccess chunk, BoundingBox region,
            Holder<Biome> toFill, Predicate<Holder<Biome>> filter) {
        throw new AbstractMethodError();
    }

    @SuppressWarnings("null")
    @Overwrite
    public static Either<Integer, CommandSyntaxException> fill(ServerLevel level, BlockPos rawFrom, BlockPos rawTo,
            Holder<Biome> biome, Predicate<Holder<Biome>> filter, Consumer<Supplier<Component>> messageOutput) {
        BlockPos from = quantize(rawFrom);
        BlockPos to = quantize(rawTo);
        BoundingBox region = BoundingBox.fromCorners(from, to);

        long volume = (long) region.getXSpan() * region.getYSpan() * region.getZSpan();
        int limit = level.getGameRules().get(GameRules.MAX_BLOCK_MODIFICATIONS);
        if (volume > limit) {
            return Either.right(ERROR_VOLUME_TOO_LARGE.create(limit, volume));
        }

        List<ChunkAccess> chunks = new ArrayList<>();
        for (int chunkZ = SectionPos.blockToSectionCoord(region.minZ());
                chunkZ <= SectionPos.blockToSectionCoord(region.maxZ()); chunkZ++) {
            for (int chunkX = SectionPos.blockToSectionCoord(region.minX());
                    chunkX <= SectionPos.blockToSectionCoord(region.maxX()); chunkX++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return Either.right(ERROR_NOT_LOADED.create());
                }
                chunks.add(chunk);
            }
        }

        MutableInt changedCount = new MutableInt(0);
        int minSectionY = SectionPos.blockToSectionCoord(region.minY());
        int maxSectionY = SectionPos.blockToSectionCoord(region.maxY());
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();

        for (ChunkAccess chunk : chunks) {
            if (chunk instanceof LevelChunk levelChunk) {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    BiomeFiller.fillSectionBiomes(level, levelChunk, sy);
                }
            }
            BiomeResolver resolver = makeResolver(changedCount, chunk, region, biome, filter);
            int quartX = QuartPos.fromSection(chunk.getPos().x());
            int quartZ = QuartPos.fromSection(chunk.getPos().z());
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                        .fillBiomesFromNoise(resolver, sampler, quartX, QuartPos.fromSection(sy), quartZ);
            }
            chunk.markUnsaved();
        }

        level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
        messageOutput.accept(() -> Component.translatable("commands.fillbiome.success.count",
                changedCount.intValue(), region.minX(), region.minY(), region.minZ(),
                region.maxX(), region.maxY(), region.maxZ()));
        return Either.left(changedCount.intValue());
    }
}
