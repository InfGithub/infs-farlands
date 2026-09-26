package com.inf.farlands.client.mixin.network;

import com.inf.farlands.client.network.ClientPacketHandlers;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 本模组自定义包的客户端分发点。
 *
 * <p>必须挂在这里，不能挂在 ClientboundCustomPayloadPacket.handle 的 HEAD：那一段跑在网络线程上，
 * 同线程跳转发生在 ClientCommonPacketListenerImpl.handleCustomPayload 内部，即本方法的调用点之前。
 * 挂错位置时 handler 会在网络线程上改客户端世界、光照引擎与 loadedEmptySections，后者是非线程安全的
 * fastutil 集合，与客户端主线程的进出账并发写入后数量账与表不再相符，下一次扩容在 rehash 的反向扫描
 * 上读到 -1 下标。
 *
 * <p>本方法在跳转之后执行，本模组六个 handler 因此都在客户端主线程上跑。取消原方法体只挡掉
 * "Unknown custom packet payload" 警告；该方法里另一条分支处理 BrandPayload，不在本模组注册的类型里。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPayloadDispatchMixin {

    @Inject(
            method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void farlands$dispatchPayload(CustomPacketPayload payload, CallbackInfo ci) {
        if (ClientPacketHandlers.handle(payload, (ClientPacketListener) (Object) this)) {
            ci.cancel();
        }
    }
}
