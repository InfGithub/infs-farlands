package com.inf.farlands.terrain.decorationFiller;

/**
 * 本线程是否正在跑装饰。
 *
 * <p>挂线程而不是全局标志：装饰跑在 farlands-gen 上，而那些 worker 是池里复用的线程，全局标志
 * 会污染同池正在跑的地形任务。进出必须配对且同线程，exit 走 finally。
 *
 * <p>目前只有一个消费者：装饰期不让方块实体持有 live level，见 BlockEntityLevelMixin。
 */
public final class DecorationContext {

    private static final ThreadLocal<Boolean> DECORATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DecorationContext() {
    }

    /** 进入装饰。 */
    public static void enter() {
        DECORATING.set(Boolean.TRUE);
    }

    /** 退出装饰。必须与 {@link #enter()} 同线程，且走 finally。 */
    public static void exit() {
        DECORATING.remove();
    }

    /** 本线程是否正在装饰。 */
    public static boolean isDecorating() {
        return DECORATING.get();
    }
}
