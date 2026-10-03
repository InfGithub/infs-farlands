package com.inf.farlands.terrain.decorationFiller;

/**
 * 装饰任务无法继续，放弃本次并稍后重试。它不是错误，是此刻条件不成立的信号。
 *
 * <p>来源只有取数：门只保证检查那一刻九宫格都在、都已过雕刻，而装饰体跑在 farlands-gen 上，
 * 取数发生在之后。邻居缺席、目标段没建出来、请求越出写域，都在这里抛出，由驱动留表重试。
 *
 * <p>不在这里等待：任何等待都会把池线程挂住，而主线程正忙着驱动加载，两边互为条件即双向等死。
 */
public final class DecorationAbort extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DecorationAbort(String message) {
        super(message);
    }
}
