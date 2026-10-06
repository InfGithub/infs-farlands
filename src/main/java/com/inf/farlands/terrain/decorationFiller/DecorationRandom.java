package com.inf.farlands.terrain.decorationFiller;

/**
 * 装饰放置随机的种子侧信道，ThreadLocal，genPool 线程隔离。
 *
 * <p>放置用的随机源在 vanilla 的 {@code ChunkGenerator.applyBiomeDecoration} 方法体内创建，而
 * 那个方法没有形参可以带种子，所以由装饰系统在调用之前写入本线程的种子，构造点由
 * DecorationSeedMixin 读取，出口清除。
 *
 * <p>两条边界：一是未设置时返回 null，构造点据此退回 vanilla 的唯一种子，不改变不装本模组时的
 * 行为；二是写读必须同线程，装饰体的调用内联执行，ThreadLocal 无需可见性论证。
 */
public final class DecorationRandom {

    private static final ThreadLocal<Long> SEED = new ThreadLocal<>();

    private DecorationRandom() {
    }

    /** 装饰系统在调 applyBiomeDecoration 之前写入本次的种子。 */
    public static void set(long seed) {
        SEED.set(seed);
    }

    /** 与 {@link #set} 对称的清理，走 finally。 */
    public static void clear() {
        SEED.remove();
    }

    /** 本次装饰的种子；未设置返回 null，构造点据此退回 vanilla 的唯一种子。 */
    public static Long get() {
        return SEED.get();
    }
}
