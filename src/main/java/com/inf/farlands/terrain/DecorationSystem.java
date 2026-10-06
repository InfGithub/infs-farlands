package com.inf.farlands.terrain;

import com.inf.farlands.terrain.decorationFiller.DecorationRegion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 装饰系统，决定 chunk 的地物与结构放置来源与应用方式。
 *
 * <p>装饰是独立阶段，CARVERS 之后、光照之前，依赖 fill 与雕刻的产物。由 ServerLevel 构造末尾按
 * id 在 SystemRegistries 里新建，实例随 level 走。实现必须无状态且纯方法，applyDecoration 跑
 * farlands-gen 多线程，实例被多个装饰任务共享。
 *
 * <p>取数只走交进来的 region：它按读域交出主线程备好的 chunk 句柄，池线程不得再查服务端的 chunk
 * 表。越出读域或读域内缺句柄时 region 抛
 * {@link com.inf.farlands.terrain.decorationFiller.DecorationAbort}，那是此刻条件不成立的信号，
 * 由驱动留表重试，不要换成别的异常类型：两者在驱动侧的日志档位与重试语义不同。
 *
 * <p>状态推进、标脏、补发与方块实体装载由 DecorationFiller 负责，本接口只做纯应用。
 */
public interface DecorationSystem {

    /** 对该 chunk 应用全部地物与结构放置。 */
    void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region);
}
