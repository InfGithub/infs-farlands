package com.inf.farlands.terrain.terrainFiller;

/**
 * 当前 fill 维度的侧信道，ThreadLocal，genPool 线程隔离。
 *
 * NoiseChunk 构造时的 finalDensity 与 aquifer 注入需要知道当前填的是哪个维度，
 * DensityFunction 构造没有 level 引用，因此由各 TerrainFiller.fill 入口 set、出口 clear。
 */
public final class TerrainFillerContext {

    public enum TerrainDimension {
        OVERWORLD,
        NETHER,
        END
    }

    private static final ThreadLocal<TerrainDimension> DIMENSION = new ThreadLocal<>();

    private TerrainFillerContext() {
    }

    public static void set(TerrainDimension dim) {
        DIMENSION.set(dim);
    }

    public static void clear() {
        DIMENSION.remove();
    }

    /** 当前 fill 维度。未设置即非 fill 上下文时默认主世界。 */
    public static TerrainDimension get() {
        TerrainDimension d = DIMENSION.get();
        return d != null ? d : TerrainDimension.OVERWORLD;
    }
}
