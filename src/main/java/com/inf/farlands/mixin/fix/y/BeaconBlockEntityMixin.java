package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 信标的竖直边界：光束扫描上限与三处维度下界。
 *
 * 光束循环以 level.getHeight(WORLD_SURFACE, x, z) 为上界。极端 Y 的信标处地表远低于信标自身 Y，
 * 循环恒不进入，每 tick 空转重置，表现为无光束、无药水效果。改成取地表与 信标 Y + maxCapIter
 * 的较大者，并按可玩范围的顶封顶，极端 Y 的光束最多 maxCapIter 格，地下与空中的信标一并修好。
 *
 * tick、updateBase、setLevel 另各有一处 level.getMinY()，运行期是 -64：lastCheckY 的回退值与
 * updateBase 的基座下界都挂在它上面，窗口滑到其下时信标不工作。三处同值替换。
 *
 * tick 是 static，@Redirect handler 拿不到信标坐标，故用 @Inject HEAD 把 pos 存进 ThreadLocal；
 * 下一次 HEAD 覆盖，异常路径的残留无害。
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {

    @Unique
    private static final ThreadLocal<BlockPos> farlands$tickPos = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private static void farlands$captureTickPos(Level level, BlockPos pos, BlockState state,
            BeaconBlockEntity blockEntity, CallbackInfo ci) {
        farlands$tickPos.set(pos);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private static int farlands$beamCeiling(Level level, Heightmap.Types type, int x, int z) {
        BlockPos pos = farlands$tickPos.get();
        int y = pos != null ? pos.getY() : WorldBounds.MIN_PLAYABLE_BLOCK;
        long ceiling = Math.min((long) WorldBounds.MAX_PLAYABLE_BLOCK, (long) y + (long) FarlandsConfig.maxCapIter);
        return Math.max(level.getHeight(type, x, z), (int) ceiling);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int farlands$minYTick(Level level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "updateBase", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int farlands$minYUpdateBase(Level level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }

    @Redirect(method = "setLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private static int farlands$minYSetLevel(Level level) {
        return WorldBounds.MIN_PLAYABLE_BLOCK;
    }
}
