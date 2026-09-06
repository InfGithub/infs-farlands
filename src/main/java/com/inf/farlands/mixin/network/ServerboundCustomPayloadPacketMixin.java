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

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void onHandle(ServerCommonPacketListener listener, CallbackInfo ci) {
        if (listener instanceof ServerGamePacketListenerImpl serverListener) {
            CustomPacketPayload payload = ((ServerboundCustomPayloadPacket) (Object) this).payload();
            if (ServerPacketHandlers.handle(payload, serverListener)) {
                ci.cancel();
            }
        }
    }
}