package com.inf.farlands.client.mixin.fix.y;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 流体雾判据的方块 Y 不落 float。
 *
 * <p>
 * vanilla 的 getFluidInCamera 把方块 Y 收窄成 float 再加流体面高度，字节码是 i2f、fadd、f2d，
 * 而眼位是 double。原版竖直只有 320 格，float 精确，这一点看不出来；本 port 的 Y 到 ±2^31，
 * float 在 2^31 处间距 128，面高被那次加法吞掉，右边退化成方块 Y 的舍入值。眼在流体里却判成在
 * 面之上，雾类型就判不出来：岩浆里没有红雾，水下没有蓝雾，而同一条链上的方块渲染与实体流体
 * 交互分别是段数据与 double 运算，都不受影响。
 *
 * <p>
 * 判据与 vanilla 逐字一致，只把两处比较换成 double，位置与流体面按精确值比。|Y| 小于 2^20 时
 * 两版结论在 6 厘米以内一致，之上是这次有意纠正的区域。
 *
 * <p>
 * NearPlane.forward 是私有字段且没有公开读取口，用公开的 getPointOnPlane(0, 0) 取同一个点。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private boolean initialized;

    @Shadow
    private Level level;

    @Shadow
    private Vec3 position;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos blockPosition;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract Camera.NearPlane getNearPlane(float fov);

    @Overwrite
    public FogType getFluidInCamera() {
        if (!this.initialized) {
            return FogType.NONE;
        }
        FluidState fluidState = this.level.getFluidState(this.blockPosition);
        if (fluidState.is(FluidTags.WATER)
                && this.position.y < (double) this.blockPosition.getY()
                        + (double) fluidState.getHeight(this.level, this.blockPosition)) {
            return FogType.WATER;
        }
        Camera.NearPlane nearPlane = this.getNearPlane(this.minecraft.options.fov().get().intValue());
        List<Vec3> points = Arrays.asList(
                nearPlane.getPointOnPlane(0.0F, 0.0F),
                nearPlane.getTopLeft(),
                nearPlane.getTopRight(),
                nearPlane.getBottomLeft(),
                nearPlane.getBottomRight());
        for (Vec3 point : points) {
            Vec3 offsetPos = this.position.add(point);
            BlockPos checkPos = BlockPos.containing(offsetPos);
            FluidState fluidStateAtPoint = this.level.getFluidState(checkPos);
            if (fluidStateAtPoint.is(FluidTags.LAVA)) {
                if (offsetPos.y <= (double) checkPos.getY()
                        + (double) fluidStateAtPoint.getHeight(this.level, checkPos)) {
                    return FogType.LAVA;
                }
            } else {
                BlockState blockState = this.level.getBlockState(checkPos);
                if (blockState.is(Blocks.POWDER_SNOW)) {
                    return FogType.POWDER_SNOW;
                }
            }
        }
        return FogType.NONE;
    }
}
