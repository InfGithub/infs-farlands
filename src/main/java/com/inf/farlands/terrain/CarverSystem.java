package com.inf.farlands.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 雕刻系统，决定 chunk 的洞穴与峡谷来源与应用方式。
 *
 * carvers 是独立阶段，SURFACE 之后、光照之前。
 * 由 CarverSystemFactory 按 level 的维度在 ServerLevel 实例化时建一份，实例随 level 走，当前只有 VOID。
 * 实现必须无状态且纯方法，applyCarvers 跑 genPool 多线程，实例被多个 carver 任务共享。
 */
public interface CarverSystem {

    /** 对目标 chunk 应用全部 carver。状态推进、标脏与高度图 prime 由 CarverFiller 负责。 */
    void applyCarvers(ServerLevel level, ChunkAccess chunk);
}
