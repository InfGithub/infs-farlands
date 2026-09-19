package com.inf.farlands.client.register;

import com.inf.farlands.client.register.packet.*;

public class FarlandsRegister {
    public static void registerStatic() {
        ClampTogglePacketRegister.registerType();
    }

    public static void register() {
        ChunkDataPacketRegister.registerHandler();
        ClampStatePacketRegister.registerHandler();
        LightUpdatePacketRegister.registerHandler();
        SectionBlocksUpdatePacketRegister.registerHandler();
    }
}
