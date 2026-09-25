package com.inf.farlands.util.window;

/**
 * 渲染编译期间的窗口基准钉住。
 *
 * <p>
 * 客户端主线程每帧把视野内每个 chunk 的窗口拉到相机段，而渲染编译跑在工作线程上。编译路径先算
 * section 索引，再取 section 数组，两处各读一次 chunk 的窗口基准；主线程在两次读之间移动窗口，
 * 索引与数组就错开，编译取到另一个绝对 Y 的段内容，而网格仍挂在本段的 origin 上。
 *
 * <p>
 * 用法：算索引的那一处先 {@link #pin}，建数组的那一处用 {@link #baseOf} 取值，读完 {@link #clear}。
 * 钉住与消费在同一次编译内成对，且 SectionCopy 只有一个构造点，所以残留的钉住值只会被下一次
 * pin 覆盖。唯一不消费的分支是占位 chunk，那时残留的是客户端缓存本就持有的共享实例。
 */
public final class WindowSnapshot {

    private WindowSnapshot() {
    }

    private record Pin(WindowedChunk chunk, int base) {
    }

    private static final ThreadLocal<Pin> PIN = new ThreadLocal<>();

    public static void pin(WindowedChunk chunk, int base) {
        PIN.set(new Pin(chunk, base));
    }

    /** 该 chunk 的钉住基准，未钉住或 chunk 身份不符则返回它的实时窗口下界。 */
    public static int baseOf(WindowedChunk chunk) {
        Pin pin = PIN.get();
        return pin != null && pin.chunk() == chunk ? pin.base() : chunk.getWindowMinY();
    }

    public static void clear(WindowedChunk chunk) {
        Pin pin = PIN.get();
        if (pin != null && pin.chunk() == chunk) {
            PIN.remove();
        }
    }
}
