package com.inf.farlands.mixin.serialize;

import com.inf.farlands.serialize.SectionLifecycle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fsa 的卸载写盘触发点。
 *
 * 旧仓库的触发点在 ChunkMapMixin.scheduleUnload 这个 @Overwrite 里，位置是
 * level.unload(levelchunk) 之前调用 SectionLifecycle.flushChunk。本 port 的等价位点就是
 * ServerLevel.unload(LevelChunk) 的 HEAD，语义相同，都是 chunk 真正卸载之前，线程也相同，
 * unloadQueue 在主线程。这样可以不必整体覆写 scheduleUnload，那只覆写会连带窗口与其它
 * 卸载逻辑，超出 fsa 范围。
 *
 * ServerLevel.unload 在 26.1.2 仍是 public，已 javap 核实。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
    private void farlands$flushChunkBeforeUnload(LevelChunk chunk, CallbackInfo ci) {
        SectionLifecycle.flushChunk(chunk);
    }
}
