package com.inf.farlands.mixin.fix.xz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

/**
 * StructurePiece.makeBoundingBox 的端点饱和。
 *
 * <p>件盒由 {@code x + width - 1} 这类 int 加法算出来，而极角处区块的方块基址已经贴着 int 上界，加完
 * 就回绕成反序参数，交给 BoundingBox 的构造器后被对调成跨全 int 的巨盒。这里在算式之后、进构造器之前
 * 把六个端点夹到可表示范围内，边界与 BoundingBox 那一族同值。
 *
 * <p>两个轴分支逐行对照原版，只多一层夹取；正常坐标逐位不变。
 */
@Mixin(StructurePiece.class)
public class StructurePieceMixin {

    /** 饱和上界。 */
    @Unique
    private static final int FARLANDS_MAX_ENDPOINT = FarlandsConstant.MAX_BLOCK - 2;

    /** 饱和下界，与上界对称。 */
    @Unique
    private static final int FARLANDS_MIN_ENDPOINT = ~FARLANDS_MAX_ENDPOINT;

    @Unique
    private static boolean farlands$inRange(long value) {
        return value >= FARLANDS_MIN_ENDPOINT && value <= FARLANDS_MAX_ENDPOINT;
    }

    @Unique
    private static int farlands$saturate(long value) {
        return value > FARLANDS_MAX_ENDPOINT ? FARLANDS_MAX_ENDPOINT
                : value < FARLANDS_MIN_ENDPOINT ? FARLANDS_MIN_ENDPOINT : (int) value;
    }

    @Inject(method = "makeBoundingBox(IIILnet/minecraft/core/Direction;III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;", at = @At("HEAD"), cancellable = true)
    private static void farlands$saturateBounds(int x, int y, int z, Direction direction, int width, int height,
            int depth, CallbackInfoReturnable<BoundingBox> cir) {
        boolean zAxis = direction.getAxis() == Direction.Axis.Z;
        long maxX = (long) x + (zAxis ? width : depth) - 1L;
        long maxY = (long) y + height - 1L;
        long maxZ = (long) z + (zAxis ? depth : width) - 1L;
        if (farlands$inRange(x) && farlands$inRange(y) && farlands$inRange(z)
                && farlands$inRange(maxX) && farlands$inRange(maxY) && farlands$inRange(maxZ)) {
            return;
        }
        cir.setReturnValue(new BoundingBox(
                farlands$saturate(x), farlands$saturate(y), farlands$saturate(z),
                farlands$saturate(maxX), farlands$saturate(maxY), farlands$saturate(maxZ)));
    }
}
