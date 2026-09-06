package com.inf.farlands.network.debug.tool.clamp;

import com.inf.farlands.InfSFarlands;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ClampStatePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<ClampStatePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfSFarlands.MOD_ID, "clamp_state"));

    public static final StreamCodec<FriendlyByteBuf, ClampStatePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.enabled()),
            buffer -> new ClampStatePacket(buffer.readBoolean()));

    @Override
    public Type<ClampStatePacket> type() {
        return TYPE;
    }
}