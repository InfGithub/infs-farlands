package com.inf.farlands.mixin.fix.xz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * BoundingBox 端点算术的饱和语义。
 *
 * <p>这个类不知道「界内」这回事：构造器只判 max 是否小于 min。原版世界里坐标被 ±30M 约束，端点加法
 * 不会越界，所以这里没有为越界定义行为；本 port 把坐标推到 int 全域之后，回绕出来的反序参数会被构造器
 * 对调成跨全 int 的巨盒，跨度算出来是负数。本类在端点算出来之后、进构造器之前，把六个值夹到可表示
 * 范围内，反序因此不再被造出来。
 *
 * <p>夹取是单调的，保序；两端本来就在范围内时逐位不变，所以正常坐标没有行为变化。边界取
 * MAX_BLOCK - 2 及其按位取反，让下游的 max + 1 与 i++ 两种写法都停在 int 内，与 fromCorners 那一处
 * 同值。
 *
 * <p>构造器自身的反序对调与解码路径都保持现状。
 */
@Mixin(BoundingBox.class)
public class BoundingBoxMixin {

    /** 饱和上界。 */
    @Unique
    private static final int FARLANDS_MAX_ENDPOINT = FarlandsConstant.MAX_BLOCK - 2;

    /** 饱和下界，与上界对称。 */
    @Unique
    private static final int FARLANDS_MIN_ENDPOINT = ~FARLANDS_MAX_ENDPOINT;

    @Shadow
    private int minX, minY, minZ, maxX, maxY, maxZ;

    @Unique
    private static boolean farlands$inRange(long value) {
        return value >= FARLANDS_MIN_ENDPOINT && value <= FARLANDS_MAX_ENDPOINT;
    }

    @Unique
    private static int farlands$saturate(long value) {
        return value > FARLANDS_MAX_ENDPOINT ? FARLANDS_MAX_ENDPOINT
                : value < FARLANDS_MIN_ENDPOINT ? FARLANDS_MIN_ENDPOINT : (int) value;
    }

    /**
     * 原地搬移的饱和。取消只在真的夹住时发生，其余情况放行原方法，写进去的值逐位相同。
     */
    @Inject(method = "move(III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;", at = @At("HEAD"), cancellable = true)
    private void farlands$saturateMove(int dx, int dy, int dz, CallbackInfoReturnable<BoundingBox> cir) {
        long nMinX = (long) this.minX + dx;
        long nMinY = (long) this.minY + dy;
        long nMinZ = (long) this.minZ + dz;
        long nMaxX = (long) this.maxX + dx;
        long nMaxY = (long) this.maxY + dy;
        long nMaxZ = (long) this.maxZ + dz;
        if (farlands$inRange(nMinX) && farlands$inRange(nMinY) && farlands$inRange(nMinZ)
                && farlands$inRange(nMaxX) && farlands$inRange(nMaxY) && farlands$inRange(nMaxZ)) {
            return;
        }
        this.minX = farlands$saturate(nMinX);
        this.minY = farlands$saturate(nMinY);
        this.minZ = farlands$saturate(nMinZ);
        this.maxX = farlands$saturate(nMaxX);
        this.maxY = farlands$saturate(nMaxY);
        this.maxZ = farlands$saturate(nMaxZ);
        cir.setReturnValue((BoundingBox) (Object) this);
    }

    /**
     * 复制型搬移的饱和，取代原来「越界即返回未搬移的原盒」的处置：那个处置会让盒与调用方单独算出的
     * 位置相差整个位移量。这里返回夹过的移动结果，不动本对象。
     */
    @Inject(method = "moved(III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;", at = @At("HEAD"), cancellable = true)
    private void farlands$saturateMoved(int dx, int dy, int dz, CallbackInfoReturnable<BoundingBox> cir) {
        long nMinX = (long) this.minX + dx;
        long nMinY = (long) this.minY + dy;
        long nMinZ = (long) this.minZ + dz;
        long nMaxX = (long) this.maxX + dx;
        long nMaxY = (long) this.maxY + dy;
        long nMaxZ = (long) this.maxZ + dz;
        if (farlands$inRange(nMinX) && farlands$inRange(nMinY) && farlands$inRange(nMinZ)
                && farlands$inRange(nMaxX) && farlands$inRange(nMaxY) && farlands$inRange(nMaxZ)) {
            return;
        }
        cir.setReturnValue(new BoundingBox(
                farlands$saturate(nMinX), farlands$saturate(nMinY), farlands$saturate(nMinZ),
                farlands$saturate(nMaxX), farlands$saturate(nMaxY), farlands$saturate(nMaxZ)));
    }

    /**
     * 放大的饱和。减在 min 侧、加在 max 侧，两侧都可能越界；固定的 12 与 24 正是极角处把贴界盒推过界
     * 的两处，所以放大后的值同样在进构造器之前夹。
     */
    @Inject(method = "inflatedBy(III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;", at = @At("HEAD"), cancellable = true)
    private void farlands$saturateInflatedBy(int inflateX, int inflateY, int inflateZ,
            CallbackInfoReturnable<BoundingBox> cir) {
        long nMinX = (long) this.minX - inflateX;
        long nMinY = (long) this.minY - inflateY;
        long nMinZ = (long) this.minZ - inflateZ;
        long nMaxX = (long) this.maxX + inflateX;
        long nMaxY = (long) this.maxY + inflateY;
        long nMaxZ = (long) this.maxZ + inflateZ;
        if (farlands$inRange(nMinX) && farlands$inRange(nMinY) && farlands$inRange(nMinZ)
                && farlands$inRange(nMaxX) && farlands$inRange(nMaxY) && farlands$inRange(nMaxZ)) {
            return;
        }
        cir.setReturnValue(new BoundingBox(
                farlands$saturate(nMinX), farlands$saturate(nMinY), farlands$saturate(nMinZ),
                farlands$saturate(nMaxX), farlands$saturate(nMaxY), farlands$saturate(nMaxZ)));
    }

    /** fromCorners 的既有上界夹取，与本类的饱和共用同一个边界值。 */
    @Inject(method = "fromCorners", at = @At("RETURN"), cancellable = true)
    private static void clampFromCorners(Vec3i first, Vec3i second, CallbackInfoReturnable<BoundingBox> cir) {
        BoundingBox box = cir.getReturnValue();
        int newMaxX = Math.min(box.maxX(), FARLANDS_MAX_ENDPOINT);
        int newMaxY = Math.min(box.maxY(), FARLANDS_MAX_ENDPOINT);
        int newMaxZ = Math.min(box.maxZ(), FARLANDS_MAX_ENDPOINT);
        if (newMaxX == box.maxX() && newMaxY == box.maxY() && newMaxZ == box.maxZ()) {
            return; // 正常 box：放行
        }
        cir.setReturnValue(new BoundingBox(
                Math.min(box.minX(), newMaxX),
                Math.min(box.minY(), newMaxY),
                Math.min(box.minZ(), newMaxZ),
                newMaxX,
                newMaxY,
                newMaxZ));
    }
}
