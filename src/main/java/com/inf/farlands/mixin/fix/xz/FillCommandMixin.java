package com.inf.farlands.mixin.fix.xz;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * /fill 的体积检查挡住单轴跨度的 int 溢出。
 *
 * <p>
 * vanilla 用 {@code long area = (long)region.getXSpan() * region.getYSpan() * region.getZSpan()} 判
 * {@code area > limit}，而 {@code BoundingBox.getXSpan()} 是 {@code maxX - minX + 1} 的 int 加法：
 * 跨度接近 2^31 时它回绕成负数，area 也跟着回绕，判据恒 false 反而放行，随后
 * {@code BlockPos.betweenClosed} 会遍历数十亿格。所以不能靠返回值拦住，要在取 limit 的那一处直接抛。
 *
 * <p>
 * 单轴跨度大于 limit 蕴含体积大于 limit，与 vanilla 的体积检查语义等价；正常路径把真实 limit 交回
 * 原判据。{@code fillBlocks} 的形参含私有嵌套枚举 {@code Mode}，handler 声明不出，只能走这一处
 * redirect。handler 末尾的 {@code source} 与 {@code region} 是宿主方法形参，由 Mixin 的
 * captureTargetArgs 机制补上。
 */
@Mixin(FillCommand.class)
public abstract class FillCommandMixin {

    @Shadow
    @Final
    private static Dynamic2CommandExceptionType ERROR_AREA_TOO_LARGE;

    @Redirect(method = "fillBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private static Object farlands$rejectHugeSpan(GameRules rules, GameRule<Integer> rule, CommandSourceStack source,
            BoundingBox region) throws CommandSyntaxException {
        int limit = rules.get(rule);
        if (span(region.minX(), region.maxX()) > limit
                || span(region.minY(), region.maxY()) > limit
                || span(region.minZ(), region.maxZ()) > limit) {
            throw ERROR_AREA_TOO_LARGE.create(limit, (int) Math.min(maxSpan(region), Integer.MAX_VALUE));
        }
        return limit;
    }

    private static long span(int a, int b) {
        return Math.abs((long) b - a) + 1;
    }

    private static long maxSpan(BoundingBox box) {
        return Math.max(span(box.minX(), box.maxX()),
                Math.max(span(box.minY(), box.maxY()), span(box.minZ(), box.maxZ())));
    }
}
