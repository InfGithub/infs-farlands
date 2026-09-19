package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.expand.y.SectionBlocksUpdatePacket;

import net.minecraft.client.Minecraft;

/**
 * 批量方块变化包客户端 handler：逐格应用服务端已确认的方块状态。
 *
 * <p>
 * 与 vanilla 的 handleChunkBlocksUpdate 同语义，走
 * ClientLevel.setServerVerifiedBlockState，
 * 即绕过客户端预测校验。维度校验：tp 跨维度在途旧包丢弃。
 */
public class SectionBlocksUpdatePacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                SectionBlocksUpdatePacket.TYPE,
                (payload, context) -> {
                    if (Minecraft.getInstance().level == null) {
                        return;
                    }
                    if (!Minecraft.getInstance().level.dimension().equals(payload.dimension())) {
                        return;
                    }
                    payload.runUpdates((pos, state) -> Minecraft.getInstance().level
                            .setServerVerifiedBlockState(pos, state, 19));
                });
    }
}
