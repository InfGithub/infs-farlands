package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 活塞可推范围的上下界放宽到可玩范围。
 *
 * vanilla 的 PistonBaseBlock.isPushable 有四处门：pos.getY() < level.getMinY() 与
 * pos.getY() > level.getMaxY() 圈出可推范围，另有向下推时 pos.getY() == level.getMinY()、向上推时
 * pos.getY() == level.getMaxY() 两个端点拒绝。运行期这两个值是 -64 与 319，窗口滑出维度范围后
 * 活塞在极端 Y 恒为不可推。
 *
 * 26.1.2 的 getMaxY 是含上界，上界直接取可玩范围的顶。
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Redirect(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int farlands$minY(Level level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private static int farlands$maxY(Level level) {
        return WorldBounds.MAX_PLAYABLE_BLOCK;
    }
}
