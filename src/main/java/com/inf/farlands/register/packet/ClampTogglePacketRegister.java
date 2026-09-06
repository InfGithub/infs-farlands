package com.inf.farlands.register.packet;

import com.inf.farlands.debug.tool.clamp.ClampMode;
import com.inf.farlands.network.debug.tool.clamp.ClampStatePacket;
import com.inf.farlands.network.debug.tool.clamp.ClampTogglePacket;
import com.inf.farlands.util.network.ServerPacketHandlers;
import com.inf.farlands.util.network.Serverbounds;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public class ClampTogglePacketRegister {
    public static void registerType() {
        Serverbounds.register(new TypeAndCodec<>(
                ClampTogglePacket.TYPE,
                ClampTogglePacket.STREAM_CODEC));
    }

    public static void registerHandler() {
        ServerPacketHandlers.register(ClampTogglePacket.TYPE, (payload,
                context) -> {
            ServerPlayer player = context.player;
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                ClampMode clamp = (ClampMode) player;
                boolean enabled = !clamp.isClampEnabled();
                clamp.setClampEnabled(enabled);
                player.sendSystemMessage(
                        Component.literal(enabled ? "Clamp mode: Enabled" : "Clamp mode: Disabled"),
                        true);
                // 状态同步到客户端：客户端据此钳制预测位置，阻止预测覆盖服务端钳制
                player.connection.send(new ClientboundCustomPayloadPacket(new ClampStatePacket(enabled)));
            } else {
                player.sendSystemMessage(
                        Component.literal("Clamp mode: Requires Commands Gamemaster permission."), true);
            }

        });
    }
}
