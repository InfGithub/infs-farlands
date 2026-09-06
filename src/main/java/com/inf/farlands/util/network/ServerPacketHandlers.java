package com.inf.farlands.util.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class ServerPacketHandlers {
    private static final Map<CustomPacketPayload.Type<?>, BiConsumer<? extends CustomPacketPayload, ServerGamePacketListenerImpl>> HANDLERS = new HashMap<>();

    public static <T extends CustomPacketPayload> void register(
            CustomPacketPayload.Type<T> type,
            BiConsumer<T, ServerGamePacketListenerImpl> handler) {
        HANDLERS.put(type, handler);
    }

    @SuppressWarnings("unchecked")
    public static boolean handle(CustomPacketPayload payload, ServerGamePacketListenerImpl listener) {
        BiConsumer<CustomPacketPayload, ServerGamePacketListenerImpl> handler = (BiConsumer<CustomPacketPayload, ServerGamePacketListenerImpl>) HANDLERS
                .get(payload.type());
        if (handler != null) {
            handler.accept(payload, listener);
            return true;
        }
        return false;
    }
}