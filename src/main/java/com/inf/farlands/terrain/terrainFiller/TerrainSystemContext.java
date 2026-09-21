package com.inf.farlands.terrain.terrainFiller;

import com.inf.farlands.terrain.TerrainSystem;

/**
 * 构造 NoiseChunk 时当前系统是谁，ThreadLocal，genPool 线程隔离。
 *
 * <p>NoiseChunk 的构造器既没有 level 也没有 ChunkAccess，而它的构造过程要按当前系统替换
 * finalDensity 与 aquifer，所以构造点先把系统放进来、构造完成后清掉。
 *
 * <p>两条边界：一是必须由构造点 set，漏了就会在 {@link #get()} 抛；二是 set 与构造必须
 * 同线程，构造调用内联执行，ThreadLocal 无需可见性论证。
 */
public final class TerrainSystemContext {

    private static final ThreadLocal<TerrainSystem> SYSTEM = new ThreadLocal<>();

    private TerrainSystemContext() {
    }

    public static void set(TerrainSystem system) {
        SYSTEM.set(system);
    }

    public static void clear() {
        SYSTEM.remove();
    }

    /** 当前正在构造 NoiseChunk 的系统。未设置即抛：只有构造路径会读它。 */
    public static TerrainSystem get() {
        TerrainSystem system = SYSTEM.get();
        if (system == null) {
            throw new IllegalStateException(
                    "TerrainSystemContext 未设置：NoiseChunk 的构造点漏了 set");
        }
        return system;
    }
}
