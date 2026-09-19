package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.world.level.dimension.DimensionType;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 维度竖直度量放宽到全 Y。
 *
 * 这五个常量是全局竖直边界的总闸：LevelReader.getMinY/getHeight 直接读 dimensionType().minY()/().height()，
 * 而 LevelHeightAccessor.isOutsideBuildHeight 就是拿这两个比。本 port 的窗口能滑到维度之外，只要这
 * 里还是 vanilla 的 -64..320，所有走 isOutsideBuildHeight 的地方都会把窗口内的合法 Y 判成界外，
 * 例如 SectionStorage.getOrCreate 对维度外的 POI section 直接抛。
 *
 * 字段是运行时值而非编译期常量：Y_SIZE 由 BlockPos.PACKED_Y_LENGTH 算，MAX_Y/MIN_Y 再派生，
 * 而 codec 常量在该类的 <clinit> 里建立于此赋值之后，codec lambda 读的是字段而非内联字面量，
 * 所以 RETURN 处改写对 codec 的 intRange 校验同样生效。
 *
 * 取 MIN_Y = -MAX_BLOCK、MAX_Y = MAX_BLOCK - 1：两端差 8 格于满量程，原因是 NoiseSettings.guardY
 * 用 MAX_Y + 1 当上界，MAX_Y 取到 Integer.MAX_VALUE 会让它溢出成负数，原版维度当场被拒。
 * MAX_Y 侧另外还受 DensityFunctions 与 NoiseRouterData 里 MIN_Y*2 / MAX_Y*2 的 int 参数限制，
 * 那几处已由 DensityFunctionsLongRangeMixin / NoiseRouterDataOverflowMixin 改成 long 并饱和。
 */
@Mixin(DimensionType.class)
public class DimensionTypeMixin {

    @Shadow
    @Final
    @Mutable
    private static int MIN_Y;

    @Shadow
    @Final
    @Mutable
    private static int MAX_Y;

    @Shadow
    @Final
    @Mutable
    private static int Y_SIZE;

    @Shadow
    @Final
    @Mutable
    private static int WAY_ABOVE_MAX_Y;

    @Shadow
    @Final
    @Mutable
    private static int WAY_BELOW_MIN_Y;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void farlands$widenVerticalBounds(CallbackInfo ci) {
        MIN_Y = -FarlandsConstant.MAX_BLOCK;
        MAX_Y = FarlandsConstant.MAX_BLOCK - 1;
        Y_SIZE = Integer.MAX_VALUE;
        WAY_ABOVE_MAX_Y = Integer.MAX_VALUE;
        WAY_BELOW_MIN_Y = Integer.MIN_VALUE;
    }}
