package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.VineBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 藤蔓随机刻生长的上下界放宽到可玩范围。
 *
 * vanilla 的 VineBlock.randomTick 有两处门：向上生长判 pos.getY() < level.getMaxY()，向下蔓延
 * 判 pos.getY() > level.getMinY()。运行期两个值是维度的 319 与 -64，窗口滑出维度范围后藤蔓
 * 既不向上长也不向下蔓延。
 *
 * 26.1.2 的 getMaxY 是含上界，上界直接取可玩范围的顶。
 */
@Mixin(VineBlock.class)
public abstract class VineBlockMixin {

    @Redirect(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
    private static int farlands$maxY(ServerLevel level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }

    @Redirect(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private static int farlands$minY(ServerLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
