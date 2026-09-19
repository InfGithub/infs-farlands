package com.inf.farlands.register;

import com.inf.farlands.command.FarlandsCommandRegistry;
import com.inf.farlands.register.command.FarLandsCommands;
import com.inf.farlands.register.packet.*;

public class FarlandsRegister {
    public static void registerStatic() {
        ChunkDataPacketRegister.registerType();
        ClampStatePacketRegister.registerType();
        ClampTogglePacketRegister.registerType();
        LightUpdatePacketRegister.registerType();
        SectionBlocksUpdatePacketRegister.registerType();
    }

    public static void register() {
        ClampTogglePacketRegister.registerHandler();
        // 命令监听器只在此登记一次；Commands 每次重建（含数据包 reload）时由 CommandsMixin 统一 fire。
        FarlandsCommandRegistry.registerServer(FarLandsCommands::register);
    }
}
