package com.inf.farlands.client.register;

import com.inf.farlands.client.register.command.FarlandsFpsCommand;
import com.inf.farlands.client.register.packet.*;
import com.inf.farlands.command.FarlandsCommandRegistry;

public class FarlandsRegister {
    public static void registerStatic() {
        ClampTogglePacketRegister.registerType();
    }

    public static void register() {
        ChunkDataPacketRegister.registerHandler();
        ClampStatePacketRegister.registerHandler();
        LightUpdatePacketRegister.registerHandler();
        SectionBlocksUpdatePacketRegister.registerHandler();
        // 客户端命令监听器只登记一次；服务端命令树每次到达时由 ClientPacketListenerMixin 触发。
        FarlandsCommandRegistry.register(FarlandsFpsCommand::register);
    }
}
