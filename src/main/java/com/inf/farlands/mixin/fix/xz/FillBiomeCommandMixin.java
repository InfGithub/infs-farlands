package com.inf.farlands.mixin.fix.xz;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.datafixers.util.Either;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /fillbiome 的体积检查挡住单轴跨度的 int 溢出。
 *
 * <p>
 * 与 /fill 同源：{@code BoundingBox.getXSpan()} 的 int 加法在跨度接近 2^31 时回绕成负数，
 * {@code volume > limit} 判据跟着失效。这里在入口判原始 from/to 的单轴跨度，超限直接返回
 * {@code Either.right(ERROR_VOLUME_TOO_LARGE)}。
 *
 * <p>
 * 判据取 {@code quantize} 之前的坐标：quantize 是 {@code QuartPos.toBlock(QuartPos.fromBlock(v))}，
 * 至多挪 15 格，不影响「跨度是否大于 limit」这个量级的判定。4 参重载委托 6 参重载，所以只注入 6 参
 * 即覆盖两条入口。
 */
@Mixin(FillBiomeCommand.class)
public abstract class FillBiomeCommandMixin {

    @Shadow
    @Final
    private static Dynamic2CommandExceptionType ERROR_VOLUME_TOO_LARGE;

    @Inject(method = "fill(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;Ljava/util/function/Predicate;Ljava/util/function/Consumer;)Lcom/mojang/datafixers/util/Either;", at = @At("HEAD"), cancellable = true)
    private static void farlands$rejectHugeSpan(ServerLevel level, BlockPos rawFrom, BlockPos rawTo, Holder<Biome> biome,
            Predicate<Holder<Biome>> filter, Consumer<Supplier<Component>> messageOutput,
            CallbackInfoReturnable<Either<Integer, CommandSyntaxException>> cir) {
        long limit = level.getGameRules().get(GameRules.MAX_BLOCK_MODIFICATIONS);
        if (span(rawFrom.getX(), rawTo.getX()) > limit
                || span(rawFrom.getY(), rawTo.getY()) > limit
                || span(rawFrom.getZ(), rawTo.getZ()) > limit) {
            cir.setReturnValue(Either.right(ERROR_VOLUME_TOO_LARGE.create(limit,
                    (int) Math.min(maxSpan(rawFrom, rawTo), Integer.MAX_VALUE))));
        }
    }

    private static long span(int a, int b) {
        return Math.abs((long) b - a) + 1;
    }

    private static long maxSpan(BlockPos a, BlockPos b) {
        return Math.max(span(a.getX(), b.getX()),
                Math.max(span(a.getY(), b.getY()), span(a.getZ(), b.getZ())));
    }
}
