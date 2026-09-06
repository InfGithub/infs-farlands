package com.inf.farlands.register.packet;

import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 窗口滑动 section 包。
 */
public class ChunkDataPacketRegister {
    public static void registerType() {
        Commonbounds.registerGameplay(new TypeAndCodec<>(
                ChunkDataPacket.TYPE,
                ChunkDataPacket.STREAM_CODEC));
    }
}
