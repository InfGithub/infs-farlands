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

    /**
     * 该 payload 类型是否已登记处理器。分发侧据此决定要不要把包重投到服务端线程：
     * 未登记的类型不跳转，走 vanilla 原路，行为与不装本模组时一致。
     *
     * <p>只读查表。登记全部发生在 mod 初始化期、任何连接建立之前，之后不再写入。
     */
    public static boolean isRegistered(CustomPacketPayload payload) {
        return HANDLERS.containsKey(payload.type());
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