package com.inf.farlands.network.expand.y;

import java.util.List;

import com.inf.farlands.InfsFarlands;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * 光照增量包，自定义，替代 vanilla ClientboundLightUpdatePacket。
 *
 * 绝对 sectionY VarInt 编码，无 vanilla 的 minLightSection 范围限制：
 * ChunkHolder.sectionLightChanged 的 BitSet 索引在极端 Y 会内存爆炸，
 * 且范围 [-5,21] 挡掉极端 Y 增量。
 *
 * 维度字段：同 ChunkDataPacket，tp 跨维度在途旧包由接收端按维度丢弃。
 *
 * 数据三态，复用 ChunkDataPacket.encodeLight/decodeLight：
 * data == null → 清空该 section 层，客户端 remove，getLightValue 恢复搜索语义
 * data == [v] → 均匀层，1 字节
 * data == [0xFF + 2048] → 完整层
 *
 * 发送端：ChunkHolderMixin.broadcastChanges，affected 收集自
 * ChunkHolder.sectionLightChanged @Inject RETURN，无范围检查。
 * 接收端：注册的客户端 handler，queueSectionData + setSectionDirty。
 */
public record LightUpdatePacket(
        ResourceKey<Level> dimension,
        int chunkX,
        int chunkZ,
        List<SectionLight> sky,
        List<SectionLight> block) implements CustomPacketPayload {

    public record SectionLight(int sectionY, byte[] data) {
    }

    public static final Type<LightUpdatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "light_update"));

    public static final StreamCodec<FriendlyByteBuf, LightUpdatePacket> STREAM_CODEC = StreamCodec.of(
            LightUpdatePacket::writeTo,
            LightUpdatePacket::readFrom);

    public static void writeTo(FriendlyByteBuf buffer, LightUpdatePacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        buffer.writeInt(pkt.chunkX());
        buffer.writeInt(pkt.chunkZ());
        writeLayer(buffer, pkt.sky());
        writeLayer(buffer, pkt.block());
    }

    private static void writeLayer(FriendlyByteBuf buffer, List<SectionLight> entries) {
        buffer.writeVarInt(entries.size());
        for (SectionLight e : entries) {
            buffer.writeVarInt(e.sectionY());
            buffer.writeBoolean(e.data() != null);
            if (e.data() != null) {
                buffer.writeByteArray(e.data());
            }
        }
    }

    public static LightUpdatePacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        int cx = buffer.readInt();
        int cz = buffer.readInt();
        List<SectionLight> sky = readLayer(buffer);
        List<SectionLight> block = readLayer(buffer);
        return new LightUpdatePacket(dimension, cx, cz, sky, block);
    }

    private static List<SectionLight> readLayer(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<SectionLight> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sy = buffer.readVarInt();
            byte[] data = buffer.readBoolean() ? buffer.readByteArray() : null;
            out.add(new SectionLight(sy, data));
        }
        return out;
    }

    /** 供发送端复用：null/空层 → null，清空信号。 */
    public static byte[] encodeSectionLight(DataLayer layer) {
        if (layer == null || layer.isEmpty()) {
            return null;
        }
        return ChunkDataPacket.encodeLight(layer);
    }

    /** 供接收端 handler 复用：null → null，客户端 remove。 */
    public static DataLayer decodeSectionLight(byte[] data) {
        return data == null ? null : ChunkDataPacket.decodeLight(data);
    }

    @Override
    public Type<LightUpdatePacket> type() {
        return TYPE;
    }
}
