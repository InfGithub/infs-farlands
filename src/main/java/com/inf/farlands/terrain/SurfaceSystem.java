package com.inf.farlands.terrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 地表系统，决定 chunk 的地表规则来源与应用方式。
 *
 * surface 是独立阶段，TERRAIN 之后、光照之前，依赖 fill 产出的高度图。
 * 由 SurfaceSystemRegistry 按维度配置选择并持有单例，当前只有 VOID。
 * 实现必须无状态且纯方法，applySurface 跑 genPool 多线程，实例被多个 surface 任务共享。
 */
public interface SurfaceSystem {

    /** 把地表规则应用到该 chunk。状态推进与标脏由 SurfaceFiller 负责，本接口只做纯应用。 */
    void applySurface(ServerLevel level, ChunkAccess chunk);
}
