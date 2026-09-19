package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowSendState;

import java.util.List;
import java.util.Map;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * chunk 包只写窗口内的 section，且按绝对 sectionY 显式带坐标，与客户端读侧配套。
 *
 * 两个方法必须共用 WindowSendState.sendableSections：一个定 buffer 大小，一个往里写，两次遍历
 * 的集合不一致就会错位。26.1.2 的 vanilla 在 extractChunkData 末尾有写满断言，所以尺寸要用
 * VarInt.getByteSize 精确算，不能像 1.21.1 那样按固定 5 字节粗算。
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public class ClientboundLevelChunkPacketDataMixin {

    @Overwrite
    private static int calculateChunkSize(LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> sections = WindowSendState.sendableSections(chunk);
        int size = VarInt.getByteSize(sections.size());
        for (Map.Entry<Integer, LevelChunkSection> e : sections) {
            size += VarInt.getByteSize(e.getKey()) + e.getValue().getSerializedSize();
        }
        return size;
    }

    @Overwrite
    public static void extractChunkData(FriendlyByteBuf buffer, LevelChunk chunk) {
        List<Map.Entry<Integer, LevelChunkSection>> toSend = WindowSendState.sendableSections(chunk);
        buffer.writeVarInt(toSend.size());
        for (Map.Entry<Integer, LevelChunkSection> e : toSend) {
            buffer.writeVarInt(e.getKey());
            e.getValue().write(buffer);
        }
    }
}
