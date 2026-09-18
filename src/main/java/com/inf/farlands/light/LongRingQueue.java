package com.inf.farlands.light;

/**
 * long 环形队列，传播队列专用，替代 fastutil LongArrayFIFOQueue。
 *
 * fastutil LongArrayFIFOQueue 在 enqueue 时扩容 expand、dequeue 时自动缩容
 * reduce 播种/传播高峰期队列大进大出 -> 扩-缩反复交替 -> 每次 resize 分配
 * 新数组，旧数组成垃圾 -> 分配风暴 + Young GC 压力。
 *
 * 本实现：enqueue 满时翻倍扩容，**dequeue 不缩容** 容量增长到播种峰值后保持，
 * 后续任务复用，引擎实例池共享 resize 分配从"每任务多次"降到"每队列一次"。
 * 峰值驻留 = 队列容量，播种峰值约 MB 级/队列，远小于震荡分配量。
 *
 * 非线程安全：每个引擎实例的任务独占，传播批次单线程，无并发访问。
 */
public final class LongRingQueue {

    private long[] buf;
    private int head;
    private int size;

    public LongRingQueue(int capacity) {
        this.buf = new long[Math.max(16, capacity)];
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public void enqueue(long v) {
        if (this.size == this.buf.length) {
            // 翻倍扩容且不缩容：拷贝 head 起的 size 个元素到新数组头部
            long[] nb = new long[this.buf.length << 1];
            int n = this.size;
            int h = this.head;
            for (int i = 0; i < n; i++) {
                nb[i] = this.buf[(h + i) & (this.buf.length - 1)];
            }
            this.buf = nb;
            this.head = 0;
        }
        this.buf[(this.head + this.size) & (this.buf.length - 1)] = v;
        this.size++;
    }

    public long dequeueLong() {
        long v = this.buf[this.head];
        this.head = (this.head + 1) & (this.buf.length - 1);
        this.size--;
        return v;
    }
}
