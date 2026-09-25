package com.inf.farlands.mixin.fix.xz;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ChunkMap.getVisibleChunkIfPresent 的访问器。
 *
 * <p>
 * 26.1.2 把该方法由 public 收成 protected，从别的包直接调编译不过。WorldGenRegionMixin 的
 * 取 chunk 兜底需要它：{@code StaticCache2D.get} 对越界坐标抛 IllegalArgumentException，那时只能问
 * 可见 chunk 表。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder farlands$getVisibleChunkIfPresent(long key);
}
