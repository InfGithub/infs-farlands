package com.inf.farlands.client.mixin.render;

import java.util.Map;

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
 * 合成数组，索引仍用同一套 windowSectionIndexFromY 映射。
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

        int maxY = Integer.MIN_VALUE;
        for (Map.Entry<Integer, LevelChunkSection> e : all.entrySet()) {
            if (e.getValue() != null && e.getKey() > maxY) {
                maxY = e.getKey();
            }
        }
        int length = all.isEmpty() ? 0 : wc.windowSectionIndexFromY(maxY) + 1;
        if (length <= 0) {
            return new LevelChunkSection[0];
        }
        LevelChunkSection[] out = new LevelChunkSection[length];
        for (Map.Entry<Integer, LevelChunkSection> e : all.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            int idx = wc.windowSectionIndexFromY(e.getKey());
            if (idx >= 0 && idx < length) {
                out[idx] = e.getValue();
            }
        }
        return out;
    }
}
