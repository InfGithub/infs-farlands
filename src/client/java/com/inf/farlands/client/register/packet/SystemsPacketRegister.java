package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.client.network.ClientSystems;
import com.inf.farlands.network.systems.SystemsPacket;

public class SystemsPacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                SystemsPacket.TYPE,
                (payload, context) -> ClientSystems.put(payload.dimension().identifier(),
                        payload.selection()));
    }
}
