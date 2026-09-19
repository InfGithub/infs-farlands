package com.inf.farlands.network.expand.y;

import java.util.function.BiConsumer;

import com.inf.farlands.InfsFarlands;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 批量方块变化包，替代 vanilla 的 ClientboundSectionBlocksUpdatePacket。
 *
 * 那个包的 sectionPos 走 SectionPos.STREAM_CODEC 位打包，超出打包位宽的 Y 会在客户端解成
 * 垃圾坐标；而本 port 的 SectionPos 侧信道是进程内的，跨进程传不过去。本包用 3int 显式写
 * section 坐标，无位宽限制。
 *
 * 维度字段与 ChunkDataPacket 同：tp 跨维度在途的旧包由接收端按维度丢弃。
 */
public record SectionBlocksUpdatePacket(
        ResourceKey<Level> dimension,
        SectionPos sectionPos,
        short[] positions,
        BlockState[] states) implements CustomPacketPayload {

    public static final Type<SectionBlocksUpdatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "section_blocks"));

    public static final StreamCodec<FriendlyByteBuf, SectionBlocksUpdatePacket> STREAM_CODEC = StreamCodec.of(
            SectionBlocksUpdatePacket::writeTo,
            SectionBlocksUpdatePacket::readFrom);

    public static void writeTo(FriendlyByteBuf buffer, SectionBlocksUpdatePacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        buffer.writeInt(pkt.sectionPos().x());
        buffer.writeInt(pkt.sectionPos().y());
        buffer.writeInt(pkt.sectionPos().z());
        buffer.writeVarInt(pkt.positions().length);
        for (int i = 0; i < pkt.positions().length; i++) {
            buffer.writeVarLong((long) Block.getId(pkt.states()[i]) << 12 | pkt.positions()[i]);
        }
    }

    public static SectionBlocksUpdatePacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        SectionPos sectionPos = SectionPos.of(buffer.readInt(), buffer.readInt(), buffer.readInt());
        int count = buffer.readVarInt();
        short[] positions = new short[count];
        BlockState[] states = new BlockState[count];
        for (int i = 0; i < count; i++) {
            long packed = buffer.readVarLong();
            positions[i] = (short) (packed & 4095L);
            states[i] = Block.BLOCK_STATE_REGISTRY.byId((int) (packed >>> 12));
        }
        return new SectionBlocksUpdatePacket(dimension, sectionPos, positions, states);
    }

    @Override
    public Type<SectionBlocksUpdatePacket> type() {
        return TYPE;
    }

    public void runUpdates(BiConsumer<BlockPos, BlockState> consumer) {
        for (int i = 0; i < this.positions.length; i++) {
            consumer.accept(this.sectionPos.relativeToBlockPos(this.positions[i]), this.states[i]);
        }
    }
}
