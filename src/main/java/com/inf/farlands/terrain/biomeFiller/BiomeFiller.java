package com.inf.farlands.terrain.biomeFiller;

import com.inf.farlands.serialize.SectionStage;
import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.system.biome.BiomeSystemRegistry;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * BIOMES 阶段编排：何时填、状态推进到 BIOMES、按维度分派 BiomeSystem。
 *
 * biome 是独立阶段，不依赖地形内容，由触发链驱动：短路完成时 fillChunkBiomes 在后台线程按
 * 窗口并集填，窗口滑入新 section 时 fillSectionBiomes 在主线程补填。fill 不再管 biome，
 * 读回 section 的磁盘 stage 恢复后自动跳过。
 *
 * setStage 线程安全：stage 载体是分段 map 加 CHM，任意线程可写。
 */
public final class BiomeFiller {

    private BiomeFiller() {
    }

    /** 短路完成：窗口并集内每 section 填 biome 并升 BIOMES。后台线程。 */
    public static void fillChunkBiomes(ServerLevel level, LevelChunk chunk) {
        BiomeSystem sys = systemFor(level);
        EntitySectionWindow.forEachSectionInAnyWindow(sy -> seedSection(level, chunk, sys, sy));
    }

    /** 窗口滑入补触发：单 section，stage 小于 BIOMES 才填。主线程。 */
    public static void fillSectionBiomes(ServerLevel level, LevelChunk chunk, int sectionY) {
        if (SectionStage.getStage(chunk, sectionY) < SectionStage.BIOMES) {
            seedSection(level, chunk, systemFor(level), sectionY);
        }
    }

    private static void seedSection(ServerLevel level, LevelChunk chunk, BiomeSystem sys, int sectionY) {
        sys.fillBiomes(level, chunk, sectionY, sectionY);
        SectionStage.setStage(chunk, sectionY, SectionStage.BIOMES);
    }

    /** 按维度 id 分派。Level.NETHER 与 Level.END 是 ResourceKey，不是枚举，不能用 switch。 */
    private static BiomeSystem systemFor(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            return BiomeSystemRegistry.getTheNether();
        }
        if (level.dimension() == Level.END) {
            return BiomeSystemRegistry.getTheEnd();
        }
        return BiomeSystemRegistry.getOverworld();
    }
}
