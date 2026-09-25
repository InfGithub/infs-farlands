package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 下落方块的竖直下界放宽到可玩范围。
 *
 * vanilla 的 FallingBlock.tick 用 pos.getY() >= level.getMinY() 决定是否转成下落实体，而 getMinY
 * 运行期是维度的 -64。本 port 的窗口能滑到 -64 以下，那里合法存在的沙与砾石会被这一句判成
 * 界外，永远不下落。
 *
 * 换成 WorldBounds 的可玩下界后，只要方块在窗口内且不低于可玩范围，下落照常触发。
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private static int farlands$minY(ServerLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
