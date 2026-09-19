package com.inf.farlands.mixin.fix.y;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 高度图的极端 Y 适配。
 *
 * vanilla 的 Heightmap 把高度存成 value - minY 写进固定容量的 BitStorage，维度范围之外的高度
 * 写不进去，低于 minY 还会变成负值。本 port 的窗口段会覆盖维度范围外的 Y，所以两处都要兜：
 * setHeight 时按需扩 BitStorage 的位数，并把负的存储值钳到 0。
 *
 * update 的下界循环与 primeHeightmaps 的上界都要钳，否则窗口覆盖极端 Y 的 chunk 每列会迭代
 * 21 亿次。
 */
@Mixin(Heightmap.class)
public class HeightmapMixin {

    @Shadow
    @Final
    @Mutable
    private BitStorage data;

    @Shadow
    @Final
    private ChunkAccess chunk;

    @Inject(method = "setHeight", at = @At("HEAD"))
    private void ensureCapacity(int x, int z, int value, CallbackInfo ci) {
        int minY = this.chunk.getMinY();
        long storedNew = (long) value - (long) minY;
        long maxStoredOld = (1L << this.data.getBits()) - 1L;
        if (storedNew <= maxStoredOld)
            return;

        long maxStored = Math.max(storedNew, maxStoredOld);
        if (maxStored < 0L)
            maxStored = 0L;
        int bits = 64 - Long.numberOfLeadingZeros(maxStored);
        if (bits < 1)
            bits = 1;
        if ((bits & (bits - 1)) != 0) {
            // 向上取到 2 的幂：SimpleBitStorage 的 raw 长度是 ceil(256 / (64 / bits))，只有
            // 2 的幂才恰好等于 4 * bits，客户端 setRawData 才能由 raw 长度反推出位数
            bits = Integer.highestOneBit(bits) << 1;
        }
        SimpleBitStorage news = new SimpleBitStorage(bits, 256);
        for (int i = 0; i < 256; i++)
            news.set(i, this.data.get(i));
        this.data = news;
    }

    @ModifyArg(method = "setHeight", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/BitStorage;set(II)V"), index = 1)
    private int clampNegativeValue(int value) {
        return Math.max(value, 0);
    }

    @Unique
    private static final ThreadLocal<Integer> currentUpdateY = new ThreadLocal<>();

    @Inject(method = "update", at = @At("HEAD"))
    private void captureUpdateY(int x, int y, int z, BlockState state, CallbackInfoReturnable<Boolean> ci) {
        currentUpdateY.set(y);
    }

    /**
     * 只钳 update 里的第一处 getMinY，即空气填充循环的下界。第二处是全空气时把高度重置为
     * minY，钳了会把重置值改成 y - 2048，属于改错；所以钉 ordinal 0。
     */
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getMinY()I", ordinal = 0))
    private int capUpdateLoop(ChunkAccess chunk) {
        Integer yObj = currentUpdateY.get();
        int vanillaMin = chunk.getMinY();
        if (yObj == null)
            return vanillaMin;
        int cap = yObj - 2048;
        return Math.max(vanillaMin, cap);
    }

    /**
     * 服务端扩容后序列化出的 long[] 比客户端默认分配大，vanilla 会整份丢弃并打警告。这里改为
     * 重建匹配大小的 BitStorage 再复制。位数由数组长度反推：size 固定 256，raw 长度 = 4 * bits。
     */
    @Overwrite
    public void setRawData(ChunkAccess chunk, Heightmap.Types type, long[] data) {
        long[] along = this.data.getRaw();
        if (along.length == data.length) {
            System.arraycopy(data, 0, along, 0, data.length);
        } else {
            int bits = Math.max(1, data.length / 4);
            SimpleBitStorage news = new SimpleBitStorage(bits, 256);
            System.arraycopy(data, 0, news.getRaw(), 0, data.length);
            this.data = news;
        }
    }

    /** 钳 primeHeightmaps 的扫描上界，窗口覆盖极端 Y 时每列不会迭代 21 亿次。 */
    @SuppressWarnings("removal")
    @Redirect(method = "primeHeightmaps", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getHighestSectionPosition()I"))
    private static int capPrimeHeightmapsLoop(ChunkAccess chunk) {
        int raw = chunk.getHighestSectionPosition();
        int cap = chunk.getMinY() + 2048;
        return Math.min(raw, cap);
    }
}
