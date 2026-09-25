package com.inf.farlands.terrain;

import net.minecraft.world.level.levelgen.Beardifier;

/**
 * 结构性地形适配数据的载体，由 ChunkAccessMixin 注入到 ChunkAccess。
 *
 * <p>存在的理由只有一条：{@link Beardifier#forStructuresInChunk} 会经 StructureManager 走
 * ServerChunkCache 的取 chunk，而那条路在非主线程上会把活踢回主线程并阻塞等待。本 port 的
 * 生成跑在 farlands-gen 上，一旦主线程正在等生成收尾，也就是关服那一刻，两边互为条件即死锁。所以这份
 * 数据必须在主线程算好存下来，生成侧只读不算。
 *
 * <p>与 {@link CarvingMaskStorage} 分开，不合并成一个「生成期数据」大接口：carving mask 是
 * 目标 chunk 的雕刻去重状态，本类是喂给 NoiseChunk 的结构适配数据，两者用途与生命周期都不同。
 */
public interface ChunkBeardifier {

    /** 由主线程在 chunk 可被生成侧读到之前算好并存入。 */
    void setBeardifier(Beardifier beardifier);

    /**
     * 取已算好的数据。生成侧调用的唯一入口。
     *
     * <p>未设置即抛：这个值只由 {@code GenerationChunkHolderMixin} 在存在流程里写入，读不到
     * 说明写入点被绕过，那种情况要让它在第一次取数时炸出来，而不是静默退化成空 Beardifier
     * 把结构上的地形适配丢掉。
     */
    Beardifier getBeardifier();
}
