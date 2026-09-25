package com.inf.farlands.client.mixin.expand.y;

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
 * <p>
 * 26.1.2 的客户端多了一个 loadedEmptySections 集合，SectionOcclusionGraph 命中它就把该段的 mesh 置成
 * EMPTY 且不放进八叉树。进集合在 ClientChunkCache.Storage.addEmptySections，出集合的唯一路径是
 * LevelChunk.setBlockState 的空/非空翻转通知。本 port 的 LevelChunk.replaceWithPacketData 是
 * {@code @Overwrite}，直接 put 新段对象，不走 setBlockState，于是有内容的段永远留在集合里，永不渲染。
 *
 * <p>
 * 挂在 RETURN 而不是改 main 源集的那个 {@code @Overwrite} 方法体：net.minecraft.client.* 在 main
 * 源集里不存在。
 *
 * <p>
 * 遍历范围必须与 vanilla 同域，即 {@code chunk.getSections()} 这个窗口数组，不能用
 * {@code windowedAllSections()}。集合的进出账在 vanilla 三处都只认窗口数组，其中
 * {@code dropEmptySections} 是 chunk 卸载时唯一的批量出栈路径；若这里按全量段写入，窗口外那些段对应的
 * 键没有任何出栈路径，集合会随加载过的 chunk 单调增长。
 *
 * <p>
 * {@code getSections()} 在本 port 返回窗口视图，{@code getSectionYFromSectionIndex} 也以窗口下界为基准，
 * 两者同域，所以索引换算与 vanilla 逐字一致。
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
        LevelChunkSection[] sections = self.getSections();
        ChunkPos pos = self.getPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            cache.onSectionEmptinessChanged(pos.x(), self.getSectionYFromSectionIndex(i), pos.z(),
                    section.hasOnlyAir());
        }
    }
}
