package com.inf.farlands.mixin.fix.xyz;

import java.util.Map;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 流体交互盒的循环上界在极端 Y 环绕。
 *
 * <p>
 * update 用 {@code Mth.ceil(box.maxY) - 1} 求三重循环的上界。实体掉到 int 下界之下后，Mth.ceil 把
 * double 窄化成 int 时饱和成 Integer.MIN_VALUE，紧随的那次减一环绕成 Integer.MAX_VALUE，于是这条
 * 递增循环从 MIN_VALUE 数到 MAX_VALUE、到顶再回绕，永不结束；同一组界还喂给 hasFluidAndLoaded 的
 * section 循环，量级同理。客户端卡在自己的 tick 里，连服务端的纠正都收不到。
 *
 * <p>
 * 处理体把饱和值抬一格，让那次减一落在 MIN_VALUE 上：循环上界变成可表示的最底一格，越界侧少算一格，
 * 而那一格在可表示范围之外。update 里三处 Mth.ceil 对应 x/y/z 三个轴，各一条重定向，共用同一个算法。
 *
 * <p>
 * 另覆写 hasFluidAndLoaded：那道门按 chunk.getSections() 的窗口数组定位段，取数面换成段容器，理由与
 * 后果见该方法的注释。
 */
@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 0))
    private static int farlands$ceilNoWrapX(double value) {
        return farlands$ceilNoWrap(value);
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 1))
    private static int farlands$ceilNoWrapY(double value) {
        return farlands$ceilNoWrap(value);
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceil(D)I", ordinal = 2))
    private static int farlands$ceilNoWrapZ(double value) {
        return farlands$ceilNoWrap(value);
    }

    /** 饱和值抬一格，使调用点的那次减一不环绕。 */
    @Unique
    private static int farlands$ceilNoWrap(double value) {
        int ceiled = Mth.ceil(value);
        return ceiled == Integer.MIN_VALUE ? Integer.MIN_VALUE + 1 : ceiled;
    }

    /**
     * 流体取数门按段容器判，不按窗口数组判。
     *
     * <p>
     * vanilla 用 {@code chunk.getSections()} 与 {@code chunk.getSectionIndexFromSectionY} 定位段。
     * 本 port 的 {@code getSections()} 返回窗口视图、{@code getSectionIndexFromSectionY} 也以窗口下界
     * 为基准，而服务端 chunk 的窗口是构造默认的死状态，长度 1，只覆盖 section -4。除 section -4 之外，
     * 任何 Y 的实体都取不到含流体的段，这道门恒假，{@code wasTouchingWater} 随之恒假：实体拿不到水的
     * 浮力与推进而下沉，鱼另外按 {@code WaterAnimal.handleAirSupply} 扣空气致死。
     *
     * <p>
     * 判据不变，仍是"相关 chunk 已加载且相关段含流体"，只把取数面换成段容器。chunk 已加载这一半保留，
     * 随后的逐格扫描因此不会经 {@code Level.getChunk} 的加载版建出 chunk。缺段不物化，与读路径同规。
     */
    @Overwrite
    private static boolean hasFluidAndLoaded(Level level, int x0, int y0, int z0, int x1, int y1, int z1) {
        int sectionX0 = SectionPos.blockToSectionCoord(x0);
        int sectionY0 = SectionPos.blockToSectionCoord(y0);
        int sectionZ0 = SectionPos.blockToSectionCoord(z0);
        int sectionX1 = SectionPos.blockToSectionCoord(x1);
        int sectionY1 = SectionPos.blockToSectionCoord(y1);
        int sectionZ1 = SectionPos.blockToSectionCoord(z1);
        for (int chunkZ = sectionZ0; chunkZ <= sectionZ1; chunkZ++) {
            for (int chunkX = sectionX0; chunkX <= sectionX1; chunkX++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return false;
                }
                Map<Integer, LevelChunkSection> sections = ((WindowedChunk) chunk).windowedAllSections();
                for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
                    LevelChunkSection section = sections.get(sectionY);
                    if (section != null && section.hasFluid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
