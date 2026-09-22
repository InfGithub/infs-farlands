package com.inf.farlands.mixin.fix.xz;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 寻路的三处边界保护。
 *
 * <p>一，目标 XZ 落在可玩范围外时无路径可算，直接返回 null。
 *
 * <p>二，createPath 的 mob.getY() &lt; level.getMinY() 判定读的是维度下界 -64。本 port 的窗口
 * 能滑到 -64 以下，那里是合法位置，该判定会把窗口内的 mob 判成不能寻路。改读世界生成下界。
 *
 * <p>三，createPath 以 fromPos.offset(±radius) 造包围盒，这两次 offset 是裸 int 加法。半径
 * 24 时，起点 x 落在可玩边界附近会让 x + radius 越过 Integer.MAX_VALUE 回绕成负，包围盒反序，
 * PathNavigationRegion 的 new ChunkAccess[xc2 - centerX + 1][...] 外维因此为负，
 * multianewarray 抛 NegativeArraySizeException，实体 tick 时崩服。这里在 long 内相加再钳进可玩
 * 范围：clamp 单调，故 start ≤ end 恒成立，chunk 维度恒 ≥ 1。
 */
@Mixin(PathNavigation.class)
public class PathNavigationMixin {

    /** 目标 XZ 出界无路径。 */
    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void farlands$skipOutOfBounds(BlockPos pos, int reachRange, CallbackInfoReturnable<Path> cir) {
        if (!WorldBounds.inBlockXZ(pos.getX(), pos.getZ())) {
            cir.setReturnValue(null);
        }
    }

    /** 下界判定改读世界生成下界，恢复 -64 以下的寻路。 */
    @Redirect(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int farlands$worldGenMinY(Level level) {
        return FarlandsConfig.worldGenMinY;
    }

    /**
     * 造包围盒的两次 offset 都经这里：先 long 相加防回绕，再把 X/Z 钳进可玩范围。Y 保持原样，
     * 只用 long 承接相加。该目标方法内 offset 恰好两处，两处都被本重定向接管。
     */
    @Redirect(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;offset(III)Lnet/minecraft/core/BlockPos;"))
    private BlockPos farlands$clampOffset(BlockPos self, int dx, int dy, int dz) {
        long nx = (long) self.getX() + dx;
        long nz = (long) self.getZ() + dz;
        long ny = (long) self.getY() + dy;
        return new BlockPos(WorldBounds.clampBlockCoord(nx), (int) ny, WorldBounds.clampBlockCoord(nz));
    }
}
