package com.inf.farlands.client.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;

import java.util.Map;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 段对象整体替换后补发空/非空通知，只做客户端。
 *
 * 26.1.2 的客户端多了一个 loadedEmptySections 集合，SectionOcclusionGraph 命中它就把该段的
 * mesh 置成 EMPTY 且不放进八叉树。进集合在 ClientChunkCache.Storage.addEmptySections，出集合
 * 的唯一路径是 LevelChunk.setBlockState 的空/非空翻转通知。本 port 的
 * LevelChunk.replaceWithPacketData 是 @Overwrite，直接 put 新段对象，不走 setBlockState，
 * 于是有内容的段永远留在集合里，永不渲染。
 *
 * 挂在 RETURN 而不是改 main 源集的那个 @Overwrite 方法体：net.minecraft.client.* 在 main
 * 源集里不存在。这里遍历 windowedAllSections 而不是窗口数组，视图外的段也能出集合。
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    @Inject(method = "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)V", at = @At("RETURN"))
    private void farlands$notifyEmptiness(FriendlyByteBuf buffer, Map<?, ?> heightmaps,
            java.util.function.Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> outputTagConsumer,
            CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!(self.getLevel() instanceof ClientLevel level)) {
            return;
        }
        if (!(level.getChunkSource() instanceof ClientChunkCache cache)) {
            return;
        }
        WindowedChunk wc = (WindowedChunk) self;
        ChunkPos pos = self.getPos();
        for (Map.Entry<Integer, LevelChunkSection> e : wc.windowedAllSections().entrySet()) {
            LevelChunkSection s = e.getValue();
            if (s == null) {
                continue;
            }
            cache.onSectionEmptinessChanged(pos.x(), e.getKey(), pos.z(), s.hasOnlyAir());
        }
    }
}
