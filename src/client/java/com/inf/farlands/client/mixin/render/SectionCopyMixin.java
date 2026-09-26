package com.inf.farlands.client.mixin.render;

import java.util.Map;

import com.inf.farlands.util.window.WindowSnapshot;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 渲染编译取数绕开窗口数组。
 *
 * SectionCopy 构造时按 getSectionIndexFromSectionY 取 getSections()[index]，而
 * getSections() 返回的是 windowSections 这个窗口视图。窗口未拉正的 chunk 只有构造默认
 * 那一段，其余索引越界得到 null，于是该 chunk 编译成空气。这里换成由 allSections 现取的
 * 合成数组，下标与 SectionCopy 收到的索引共用同一个基准：算索引的那一处由
 * {@link RenderRegionCacheMixin} 钉住，这里取同一个值，避免两次读之间窗口移动造成错位。
 *
 * 另有一处空守卫：SectionCopy 只做索引上下界检查就直接调 hasOnlyAir，而合成数组可能带洞，
 * 于是那次调用会自己 NPE。把 hasOnlyAir 的接收者换成「槽位存在则为该 section，否则视为全空气」。
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public class SectionCopyMixin {

    /** 合成数组里可能带洞，洞按该段全空气处理，避免 SectionCopy 自己 NPE。 */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean farlands$nullSectionIsAir(LevelChunkSection section) {
        return section == null || section.hasOnlyAir();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] farlands$sectionsFromAll(LevelChunk chunk) {
        WindowedChunk wc = (WindowedChunk) chunk;
        Map<Integer, LevelChunkSection> all = wc.windowedAllSections();
        // 基准取算索引时钉住的那个值，不再读实时窗口，避免两次读错开
        int base = WindowSnapshot.baseOf(wc);

        // 一次遍历同时取最高键与非空计数。
        int maxY = Integer.MIN_VALUE;
        int cnt = 0;
        for (Map.Entry<Integer, LevelChunkSection> e : all.entrySet()) {
            int k = e.getKey();
            if (e.getValue() != null) {
                if (k > maxY) {
                    maxY = k;
                }
                cnt++;
            }
        }
        // 数组只需覆盖 SectionCopy 实际访问的下标：sectionY - base。sectionY 来自
        // RenderRegionCache.createRegion，它取脏段的段号再向上下各扩一格；而脏段来自
        // visibleSections，被 ViewArea.containsSection 限在相机段上下各半高。base 就是相机段
        // 减半高，故最大可达下标为窗口段数加一，只比窗口上界高一段。
        //
        // 不设上限的后果：allSections 是历次窗口的并集，可含远离当前窗口的键。section 包与
        // 读回都按调用方给的 sectionY 直写，不判边界，于是 maxY - base 可达上亿，每次调用
        // 分配 GB 级引用数组，表现为周期性 GC 停顿。
        //
        // 上限取窗口跨度加一，恰好覆盖最大可达下标；正常情形实际跨度不超过它，与改动前逐位相同。
        int length = 0;
        if (cnt != 0) {
            int span = maxY - base + 1;
            int windowSpan = wc.getWindowMaxY() - base + 2;
            length = span <= windowSpan ? span : (windowSpan > 0 ? windowSpan : span);
        }
        if (length <= 0) {
            WindowSnapshot.clear(wc);
            return new LevelChunkSection[0];
        }
        LevelChunkSection[] out = new LevelChunkSection[length];
        for (Map.Entry<Integer, LevelChunkSection> e : all.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            int idx = e.getKey() - base;
            if (idx >= 0 && idx < length) {
                out[idx] = e.getValue();
            }
        }
        WindowSnapshot.clear(wc);
        return out;
    }
}
