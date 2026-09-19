package com.inf.farlands.client.register;

import com.inf.farlands.client.register.packet.*;

public class FarlandsRegister {
    public static void registerStatic() {
        ClampTogglePacketRegister.registerType();
    }

    public static void register() {
        ClampStatePacketRegister.registerHandler();
        LightUpdatePacketRegister.registerHandler();
        ChunkDataPacketRegister.registerHandler();
        SectionBlocksUpdatePacketRegister.registerHandler();
    }
}
