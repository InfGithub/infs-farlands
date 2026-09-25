package com.inf.farlands.mixin.fix.xz;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 刷怪取点与粗略群系查询的极端 Y 适配。
 *
 * <p>
 * {@code getRandomPosWithin} 取
 * {@code Mth.randomBetweenInclusive(level.random, level.getMinY(), topEmptyY)}，而
 * {@code Mth.randomBetweenInclusive} 算的是 {@code nextInt(maxInclusive - min + 1) + min}：本 mod
 * 的窗口能让 WORLD_SURFACE 高度远超可玩范围，{@code maxInclusive - min + 1} 会回绕成负数，
 * {@code nextInt} 随即抛 IllegalArgumentException。这里把上界夹到 {@code min + Integer.MAX_VALUE - 1}，
 * 与 min 一起保证差值落在 int 内。
 *
 * <p>
 * {@code getRoughBiome} 对 LevelChunk 改走 {@code Level.getNoiseBiome}，绕过 chunk 自身的 section
 * 索引，窗口滑到维度范围之外时也能问到群系。
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {

    @Redirect(method = "getRandomPosWithin", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;randomBetweenInclusive(Lnet/minecraft/util/RandomSource;II)I"))
    private static int farlands$clampTopY(RandomSource random, int min, int maxInclusive) {
        int high = (int) Math.min((long) maxInclusive, (long) min + Integer.MAX_VALUE - 1L);
        if (high < min) {
            high = min;
        }
        return Mth.randomBetweenInclusive(random, min, high);
    }

    @Overwrite
    private static Biome getRoughBiome(BlockPos pos, ChunkAccess chunk) {
        int x = QuartPos.fromBlock(pos.getX());
        int y = QuartPos.fromBlock(pos.getY());
        int z = QuartPos.fromBlock(pos.getZ());
        if (chunk instanceof LevelChunk levelChunk) {
            return levelChunk.getLevel().getNoiseBiome(x, y, z).value();
        }
        return chunk.getNoiseBiome(x, y, z).value();
    }
}
