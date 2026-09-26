package com.inf.farlands.mixin.network;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.inf.farlands.util.network.ServerPacketHandlers;
import com.inf.farlands.util.network.Serverbounds;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mixin(ServerboundCustomPayloadPacket.class)
public class ServerboundCustomPayloadPacketMixin {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"))
    private static StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> addGameplayTypes(
            CustomPacketPayload.FallbackProvider<RegistryFriendlyByteBuf> fallback,
            List<CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> types) {
        List<CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> extended = new ArrayList<>(types);
        for (int i = 0; i < Serverbounds.gameplayBounds.size(); i++) {
            extended.add(Serverbounds.gameplayBounds.get(i));
        }
        return CustomPacketPayload.codec(fallback, extended);
    }

    /**
     * 自定义包在服务端线程上分发。
     *
     * <p>服务端没有现成的分发点可挂：ServerCommonPacketListenerImpl 与 ServerGamePacketListenerImpl 的
     * handleCustomPayload 都是空方法，没有跳转之后的第二条路径，所以这里自己触发一次同线程跳转。
     * 网络线程上 scheduleIfPossible 入队后抛 RunningOnDifferentThreadException，由 Connection 吞掉，
     * 包在 processQueuedPackets 里重投，重投时本方法再进一次，此时才真正分发并取消原处理。
     *
     * <p>只对本模组登记过的类型跳转；未登记的类型走 vanilla 原路，行为与不装本模组时逐字相同。
     */
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(ServerCommonPacketListener listener, CallbackInfo ci) {
        if (listener instanceof ServerGamePacketListenerImpl serverListener) {
            CustomPacketPayload payload = ((ServerboundCustomPayloadPacket) (Object) this).payload();
            if (ServerPacketHandlers.isRegistered(payload)) {
                PacketUtils.ensureRunningOnSameThread(
                        (ServerboundCustomPayloadPacket) (Object) this, serverListener,
                        serverListener.player.level());
                if (ServerPacketHandlers.handle(payload, serverListener)) {
                    ci.cancel();
                }
            }
        }
    }
}
