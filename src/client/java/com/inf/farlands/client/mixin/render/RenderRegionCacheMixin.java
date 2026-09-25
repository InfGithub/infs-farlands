package com.inf.farlands.client.mixin.render;

import com.inf.farlands.util.window.WindowSnapshot;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 编译取数时把窗口基准钉在算索引的那一处。
 *
 * <p>
 * 编译路径是「先算 section 索引、再取 section 数组」，两处分开读 chunk 的窗口基准，而客户端主线程
 * 每帧都可能移动它。这里在算索引时取一次基准并钉住，索引直接由这个基准算出；数组由
 * {@link SectionCopyMixin} 用同一个基准建，两次读不再错开。
 *
 * <p>
 * 该调用在合成 lambda {@code lambda$getSectionDataCopy$0} 里，全仓只有 RenderRegionCache
 * 这一个 SectionCopy 构造点，所以钉住与消费总是成对。
 */
@Mixin(RenderRegionCache.class)
public class RenderRegionCacheMixin {

    @Redirect(method = "lambda$getSectionDataCopy$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndexFromSectionY(I)I"))
    private static int farlands$pinWindowBase(LevelChunk chunk, int sectionY) {
        WindowedChunk windowed = (WindowedChunk) chunk;
        int base = windowed.getWindowMinY();
        WindowSnapshot.pin(windowed, base);
        return sectionY - base;
    }
}
