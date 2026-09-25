package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 结构方块 corner 检测的竖直扫描范围。
 *
 * vanilla 的 detectSize 用维度的全高当扫描盒，运行期是 -64 到 319，极端 Y 的结构方块 corner
 * 落在范围之外就检测不到。上界若直接取可玩范围的顶，getRelatedCorners 会扫 161 × 161 × 全高，
 * 量级爆炸。故竖直范围收成结构位置 Y ± 80，与 XZ 的 ± 80 对称。
 *
 * 代价：corner 超出结构 Y ± 80 的超高结构漏检。
 * 两端与可玩范围求交；Y ± 80 必须走 long，Y 接近 ±2.14B 时 int 会溢出。
 */
@Mixin(StructureBlockEntity.class)
public abstract class StructureBlockEntityMixin {

    @Redirect(method = "detectSize", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int farlands$minScanY(Level level) {
        return (int) Math.max((long) WorldBounds.MIN_PLAYABLE_BLOCK,
                (long) ((BlockEntity) (Object) this).getBlockPos().getY() - 80L);
    }

    @Redirect(method = "detectSize", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private int farlands$maxScanY(Level level) {
        return (int) Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK,
                (long) ((BlockEntity) (Object) this).getBlockPos().getY() + 80L);
    }
}
