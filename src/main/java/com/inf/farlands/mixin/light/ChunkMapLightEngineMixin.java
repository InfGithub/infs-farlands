package com.inf.farlands.mixin.light;

import com.inf.farlands.light.FarLandsLightEngine;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 将 ChunkMap 构造中的 {@code new ThreadedLevelLightEngine}
 * 替换为 {@link FarLandsLightEngine}。
 *
 * <p>
 * 26.1.2：构造实参的第 4/5 位由 ProcessorMailbox/ProcessorHandle 换成
 * ConsecutiveExecutor/ChunkTaskDispatcher，必须原样透传 ChunkMap 自己创建的那两个
 * 对象（light 用的 ConsecutiveExecutor 与 lightTaskDispatcher） 另行 new 会让
 * ChunkMap 持有的 dispatcher 与引擎实际使用的调度器脱钩。
 */
@Mixin(ChunkMap.class)
public class ChunkMapLightEngineMixin {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/server/level/ThreadedLevelLightEngine"))
    private ThreadedLevelLightEngine redirectNewLightEngine(
            LightChunkGetter lightChunk, ChunkMap chunkMap, boolean skyLight,
            ConsecutiveExecutor consecutiveExecutor,
            ChunkTaskDispatcher taskDispatcher) {
        return new FarLandsLightEngine(lightChunk, chunkMap, skyLight, consecutiveExecutor, taskDispatcher);
    }
}
