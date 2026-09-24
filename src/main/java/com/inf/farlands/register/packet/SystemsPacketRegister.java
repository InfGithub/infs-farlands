package com.inf.farlands.register.packet;

import com.inf.farlands.network.systems.SystemsPacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 世界系统选择下发包。
 */
public class SystemsPacketRegister {
    public static void registerType() {
        Commonbounds.registerGameplay(new TypeAndCodec<>(
                SystemsPacket.TYPE,
                SystemsPacket.STREAM_CODEC));
    }
}
