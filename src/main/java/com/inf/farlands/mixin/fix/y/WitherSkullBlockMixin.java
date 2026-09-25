package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 凋灵召唤的竖直下界放宽到可玩范围。
 *
 * vanilla 的 checkSpawn 与 canSpawnMob 各有一处 pos.getY() >= level.getMinY()，getMinY 运行期
 * 是 -64。窗口滑到其下时骷髅头摆出正确图案也召不出凋灵，canSpawnMob 那处另带 + 2 的余量。
 *
 * checkSpawn 有两个重载，含高度门的是带 SkullBlockEntity 的那个，故用描述符定位；canSpawnMob
 * 名字唯一，一并写全。+ 2 留在调用点，不进 handler。
 */
@Mixin(WitherSkullBlock.class)
public abstract class WitherSkullBlockMixin {

    @Redirect(method = {
            "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V",
            "canSpawnMob(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)Z" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int farlands$minY(Level level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
