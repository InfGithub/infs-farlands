package com.inf.farlands.client.mixin.debug;

import com.inf.farlands.serialize.SectionIO;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 坐标条目的区块行改成 section 行，给出该 section 落在哪个 fsa 文件。
 *
 * <p>
 * 本 mod 的方块与光照持久化落在 fsa，原版那行给出的 r.x.z.mca 不再指向真实的存盘位置。
 *
 * <p>
 * 一个 fsa 文件覆盖 32x32 个 chunk、32 个 section，即 512x512x512 格。文件名里的三段就是
 * 这三档的分组号，所以显示的三段取同一口径：chunk 右移 5 即 regionX，sectionY 右移 5 即
 * yBlock，chunk 右移 5 即 regionZ，与括号里的文件名逐段对应，可直接到 fsa 目录对号。
 *
 * <p>
 * 注入点是 display 里唯一一次五参 List.of，index 2 即区块行，其余四行不动。取位置用
 * Minecraft.getInstance().getCameraEntity() 重取一次，不依赖局部变量：该路径只在原方法
 * 判过 entity 非空之后可达。
 *
 * <p>
 * handler 返回 Object 而不是 String：List.of 的五个形参在字节码里都是 Object，@ModifyArg
 * 要求 handler 返回类型与被改实参的类型逐字相等，写成 String 会在注入期抛
 * InvalidInjectionException，崩在 DebugScreenEntries 的 clinit。
 */
@Mixin(DebugEntryPosition.class)
public class DebugEntryPositionMixin {

    @ModifyArg(method = "display", at = @At(value = "INVOKE", target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), index = 2)
    private Object farlands$sectionLine(Object original) {
        BlockPos pos = Minecraft.getInstance().getCameraEntity().blockPosition();
        int sx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int sz = pos.getZ() >> 4;
        return String.format(Locale.ROOT, "Section: %d %d %d [%s]", sx, sy, sz,
                SectionIO.fileName(pos.getX(), pos.getY(), pos.getZ()));
    }
}
