package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * beta 1.7.3 地形的 biome 查询侧信道。
 *
 * <p>密度函数的 compute 只拿得到坐标，没有 level 也没有 sampler，所以由填充侧在 fill 入口写入本
 * 线程的 sampler 与 biomeSource，出口清除。写读同线程，各 genPool 线程彼此隔离。
 *
 * <p>查询 Y 固定海平面 quart，对应 beta 按列取温度湿度。侧信道未设置或查不到结果时返回 null，
 * 调用方据此退化为温度湿度 0。
 */
public final class BetaContext {

    /** 海平面 63 的 quart Y。 */
    private static final int QUART_Y = QuartPos.fromBlock(63);

    private static final ThreadLocal<Climate.Sampler> SAMPLER = new ThreadLocal<>();
    private static final ThreadLocal<BiomeSource> SOURCE = new ThreadLocal<>();

    private BetaContext() {
    }

    /** fill 入口调用。 */
    public static void set(Climate.Sampler sampler, BiomeSource source) {
        SAMPLER.set(sampler);
        SOURCE.set(source);
    }

    /** fill 出口 finally 调用。 */
    public static void clear() {
        SAMPLER.remove();
        SOURCE.remove();
    }

    /** 按 quart 坐标查地表 biome。侧信道未设置时返回 null。 */
    public static Holder<Biome> biomeAt(int qx, int qz) {
        Climate.Sampler sampler = SAMPLER.get();
        BiomeSource source = SOURCE.get();
        if (sampler == null || source == null) {
            return null;
        }
        return source.getNoiseBiome(qx, QUART_Y, qz, sampler);
    }
}
