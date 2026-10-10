package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * 两类改动。其一是取索引：onSectionAdded 用 Level 基准的索引取 chunk 的窗口数组，与窗口索引差
 * windowMinY + 4，窗口外直接越界。
 *
 * <p>
 * 其二是范围，三处按 Level 的 section 范围行事，而竖直方向的真实范围是玩家窗口。onChunkAdded 与
 * onChunkRemoved 按 level 范围建删 section；isOutOfGraph 判的是相机 section 是否落在 level 范围内且
 * 图里还没有它，Level 基准下相机在窗口内、范围外的 section 被判成在图里，于是跳过本帧的剔除调度而不走
 * 等待路径。三处都换成目标 chunk 的窗口范围。
 *
 * <p>
 * 参数槽：onSectionAdded(int x, int y, int z) 为 this=0, x=1, y=2, z=3；onChunkAdded(int x, int z)
 * 与 onChunkRemoved(int x, int z) 为 this=0, x=1, z=2；isOutOfGraph(SectionPos) 为 this=0, pos=1。
 *
 * <p>
 * 取 chunk 用两参 Level.getChunk，与 Sodium 的取法同源：客户端对未加载 chunk 的两参查询返回共享的空
 * chunk 而不是 null，四参非阻塞形式返回 null，回落到 Level 基准索引就会在空 chunk 那个长度 1 的窗口
 * 数组上越界。
 */
@Pseudo
@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerMixin {

    /**
     * 横向越界的段一律不建。onSectionAdded 是全树唯一 new RenderSection 的地方，它的唯一调用方是
     * onChunkAdded 的循环，所以在 HEAD 取消即等于该段不进 renderSections、不进 RenderRegion、不进
     * 遮挡图、不产生构建任务。
     *
     * <p>
     * 判据是横向段坐标 x、z 落在可玩段范围内。这个界不需要实测：段坐标 134217727 时，Sodium 用
     * (段坐标 >> 3) << 7 推出的区域原点是 2^31，int 存不下，回绕成负值；134217726 时是 2147483520，
     * 仍在 int 内。分界点正好落在 WorldBounds.MAX_PLAYABLE_SECTION 上。
     *
     * <p>
     * 这些段对应的方块坐标在 Integer.MAX_VALUE 之外，世界上不存在那样的方块，所以拒绝它们不丢内容；
     * 而按原样放进去，它们的区域原点无法用 int 表示，只会被画到错的位置上。
     *
     * <p>
     * 竖向的 y 不在这条判据内，竖向窗口另有既有机制。
     */
    @Inject(method = "onSectionAdded", at = @At("HEAD"), cancellable = true)
    private void farlands$rejectHorizontalOutOfBounds(int x, int y, int z, CallbackInfo ci) {
        if (!WorldBounds.inSection(x) || !WorldBounds.inSection(z)) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I"))
    private int farlands$windowIndex(ClientLevel instance, int y, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 3) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        if (chunk == null) {
            return original.call(instance, y);
        }
        int index = chunk.getSectionIndexFromSectionY(y);
        int length = chunk.getSections().length;
        if (index < 0 || index >= length) {
            // 窗口滑动与 section 集合更新的竞态：错位一帧可接受，越界不可接受。
            return Math.max(0, Math.min(index, length - 1));
        }
        return index;
    }

    @WrapOperation(method = "onChunkAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSectionY()I"))
    private int farlands$addedMin(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 2) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMinY();
    }

    @WrapOperation(method = "onChunkAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSectionY()I"))
    private int farlands$addedMax(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 2) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMaxY();
    }

    @WrapOperation(method = "onChunkRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSectionY()I"))
    private int farlands$removedMin(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 2) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMinY();
    }

    @WrapOperation(method = "onChunkRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSectionY()I"))
    private int farlands$removedMax(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) int x,
            @Local(argsOnly = true, index = 2) int z) {
        ChunkAccess chunk = farlands$chunkAt(instance, x, z);
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMaxY();
    }

    @WrapOperation(method = "isOutOfGraph", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinSectionY()I"))
    private int farlands$graphMin(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) SectionPos pos) {
        ChunkAccess chunk = farlands$chunkAt(instance, pos.getX(), pos.getZ());
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMinY();
    }

    @WrapOperation(method = "isOutOfGraph", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxSectionY()I"))
    private int farlands$graphMax(ClientLevel instance, Operation<Integer> original,
            @Local(argsOnly = true, index = 1) SectionPos pos) {
        ChunkAccess chunk = farlands$chunkAt(instance, pos.getX(), pos.getZ());
        return chunk == null ? original.call(instance) : ((WindowedChunk) chunk).getWindowMaxY();
    }

    @Unique
    private static ChunkAccess farlands$chunkAt(Level level, int x, int z) {
        return level.getChunk(x, z);
    }
}
