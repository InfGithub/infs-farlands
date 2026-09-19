package com.inf.farlands.mixin.serialize;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.serialize.SectionIO;
import com.inf.farlands.serialize.SectionLifecycle;
import com.inf.farlands.terrain.pipeline.GenQueue;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fsa 的关服同步刷盘。
 *
 * 旧仓库挂在 ServerStoppingEvent 上，顺序是 awaitEncodeTasks(5000)、awaitIODrain、
 * drainMainThreadTasks、GenQueue.awaitIdle(5000)、shutdownSyncFlush。本 port 的等价位点是
 * MinecraftServer.stopServer() 的 HEAD，已 javap 核实存在且为 protected。它在最终保存之前
 * 执行，此时各维度与 chunk 都还在。
 *
 * 顺序不可调换。卸载编码任务由 flushChunk 提交到 ENCODE_POOL，必须先于 awaitIODrain 完成
 * 提交，否则其 IO 写不被等待，关服就丢已卸载的 section 数据。
 *
 * GenQueue.awaitIdle 在 IO 排空之后、同步兜底写之前，等在途生成与光照收敛，有界 5 秒。
 * 超时后的在途 section 由 shutdownSyncFlush 的 isChunkBusy 跳过兜底，重进重生成。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void farlands$fsaShutdownFlush(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        try {
            SectionLifecycle.awaitEncodeTasks(server, 5000);
            SectionIO.awaitIODrain();
            SectionIO.drainMainThreadTasks(server);
            GenQueue.awaitIdle(5000);
            SectionLifecycle.shutdownSyncFlush(server);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("farlands: fsa shutdown flush failed", e);
        }
    }
}
