package com.inf.farlands.network.systems;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.terrain.registry.SystemsData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 某维度四族系统选择的下发包。
 *
 * <p>选择本身用 {@link SystemsData.LevelSelection} 的 codec 编解码，与磁盘同一份定义：盘上怎么写、
 * 线上就怎么走，将来加参数类型只改一处。
 *
 * <p>带维度字段，接收端按维度入表，跨维度在途的旧包因此顶不掉当前维度的显示。
 */
public record SystemsPacket(ResourceKey<Level> dimension, SystemsData.LevelSelection selection)
        implements CustomPacketPayload {

    public static final Type<SystemsPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "systems"));

    public static final StreamCodec<FriendlyByteBuf, SystemsPacket> STREAM_CODEC = StreamCodec.of(
            SystemsPacket::writeTo,
            SystemsPacket::readFrom);

    /** 与磁盘同一份 codec，经 NbtOps 走线；读侧用默认配额。 */
    private static final StreamCodec<ByteBuf, SystemsData.LevelSelection> SELECTION_CODEC = ByteBufCodecs
            .fromCodec(SystemsData.LevelSelection.CODEC);

    public static void writeTo(FriendlyByteBuf buffer, SystemsPacket pkt) {
        buffer.writeResourceKey(pkt.dimension());
        SELECTION_CODEC.encode(buffer, pkt.selection());
    }

    public static SystemsPacket readFrom(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = buffer.readResourceKey(Registries.DIMENSION);
        return new SystemsPacket(dimension, SELECTION_CODEC.decode(buffer));
    }

    @Override
    public Type<SystemsPacket> type() {
        return TYPE;
    }
}
