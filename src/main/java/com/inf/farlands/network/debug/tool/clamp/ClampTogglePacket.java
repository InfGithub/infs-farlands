package com.inf.farlands.network.debug.tool.clamp;

import com.inf.farlands.InfSFarlands;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ClampTogglePacket() implements CustomPacketPayload {
    public static final Type<ClampTogglePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(InfSFarlands.MOD_ID, "clamp_toggle"));

    public static final StreamCodec<FriendlyByteBuf, ClampTogglePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
            }, buffer -> new ClampTogglePacket());

    @Override
    public Type<ClampTogglePacket> type() {
        return TYPE;
    }
}