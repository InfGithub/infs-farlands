package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.debug.tool.clamp.ClampMode;
import com.inf.farlands.network.debug.tool.clamp.ClampStatePacket;

import net.minecraft.client.Minecraft;

public class ClampStatePacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                ClampStatePacket.TYPE,
                (payload, context) -> {
                    if (Minecraft.getInstance().player instanceof ClampMode clamp) {
                        clamp.setClampEnabled(payload.enabled());
                    }
                });
    }
}
