package com.inf.farlands.mixin.compat.chunky;

import java.util.concurrent.CompletableFuture;

import com.inf.farlands.compat.chunky.ChunkyPregen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunky 1.5.3 版本绑定，升级即碎。
 *
 * <p>两处注入，对应它的驱动与完成判据：
 *
 * <ul>
 * <li>{@code isChunkGenerated(int,int)}：把结论换成「带内段全部 LIGHTED」。原实现只认内存里的
 * {@code ChunkStatus.FULL} 或存档 NBT 的 {@code Status}，而本 port 的 FULL 发给没有 stage 的空壳，于是
 * 空壳会被它判成已生成并跳过。这一处不改写，它的进度就是假的。</li>
 * <li>{@code getChunkAtAsync(int,int)}：记录这次请求、给该 chunk 的 ±8 铺票，并把返回的 future 换成
 * 「带内全亮」的完成信号。不换的话它那句 FULL 请求在本 port 里会被立刻满足，因为空壳也就绪，它那个
 * {@code Semaphore(50)} 于是跑在生成前面，速率读数没有意义。</li>
 * </ul>
 *
 * <p>两个方法都由一次外层调用加一次服务端线程上的内层调用组成，外层那次只把活 hop 回服务端线程，而两次
 * 都会走到本注入点，所以处理体按线程分流：只在内层那次动作，外层的值由内层的返回值经它的
 * {@code thenCompose} 透传上来。
 *
 * <p>加 {@code @Pseudo} 是因为 Chunky 可能缺席：目标解析不到时 Mixin 只记一条 DEBUG 并跳过本类，不报错
 * 也不加载。
 */
@Pseudo
@Mixin(FabricWorld.class)
public abstract class FabricWorldMixin {

    @Shadow
    @Final
    private ServerLevel world;

    @Inject(method = "isChunkGenerated", at = @At("RETURN"), cancellable = true)
    private void farlands$truthfulGenerated(int x, int z, CallbackInfoReturnable<CompletableFuture<Boolean>> cir) {
        if (!farlands$onServerThread()) {
            return;
        }
        cir.setReturnValue(CompletableFuture.completedFuture(ChunkyPregen.bandLit(this.world, x, z)));
    }

    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), cancellable = true)
    private void farlands$driveAndWait(int x, int z, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!farlands$onServerThread()) {
            return;
        }
        cir.setReturnValue(ChunkyPregen.begin(this.world, new ChunkPos(x, z)));
    }

    /** 外层那次只是 hop，值由内层给；票操作与 latestChunk 也都只允许在服务端线程上做。 */
    private boolean farlands$onServerThread() {
        return this.world.getServer() != null
                && Thread.currentThread() == this.world.getServer().getRunningThread();
    }
}
