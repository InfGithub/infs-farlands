package com.inf.farlands.register.packet;

import com.inf.farlands.network.expand.y.FarLandsSectionBlocksUpdatePacket;
import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

/**
 * 批量方块变化包。
 *
 * <p>
 * 3int 显式写 section 坐标，绕开 vanilla 那个包的位打包；客户端 handler 见
 * {@code com.inf.farlands.client.register.packet.SectionBlocksUpdatePacketRegister}。
 */
public class SectionBlocksUpdatePacketRegister {
    public static void registerType() {
        Commonbounds.register(new TypeAndCodec<>(
                FarLandsSectionBlocksUpdatePacket.TYPE,
                FarLandsSectionBlocksUpdatePacket.STREAM_CODEC));
    }
}
