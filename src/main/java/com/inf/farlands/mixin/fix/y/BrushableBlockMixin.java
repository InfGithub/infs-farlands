package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BrushableBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 可疑的沙与砾石下落判定的竖直下界放宽到可玩范围。
 *
 * vanilla 的 BrushableBlock.tick 与 FallingBlock.tick 共用同一道门：FallingBlock.isFree 判下方
 * 可落，pos.getY() >= level.getMinY() 判自身 Y，getMinY 运行期是 -64。窗口滑到其下时这一句会
 * 把方块判成界外，考古方块既不重置也不下落。
 *
 * 与 FallingBlock 同值替换。
 */
@Mixin(BrushableBlock.class)
public abstract class BrushableBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private static int farlands$minY(ServerLevel level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
