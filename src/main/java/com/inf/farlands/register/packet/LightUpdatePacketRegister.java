package com.inf.farlands.register.packet;

import com.inf.farlands.network.expand.y.FarLandsLightUpdatePacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 光照增量包。
 *
 * <p>
 * 绝对 sectionY 编码，无 minLightSection 范围限制；客户端 handler 见
 * {@code com.inf.farlands.client.register.packet.LightUpdatePacketRegister}。
 */
public class LightUpdatePacketRegister {
    public static void registerType() {
        Commonbounds.register(new TypeAndCodec<>(
                FarLandsLightUpdatePacket.TYPE,
                FarLandsLightUpdatePacket.STREAM_CODEC));
    }
}
