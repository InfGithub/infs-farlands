package com.inf.farlands.client.register.packet;

import com.inf.farlands.network.debug.tool.clamp.ClampTogglePacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

public class ClampTogglePacketRegister {
    public static void registerType() {
        Commonbounds.register(new TypeAndCodec<>(
                ClampTogglePacket.TYPE,
                ClampTogglePacket.STREAM_CODEC));
    }
}
