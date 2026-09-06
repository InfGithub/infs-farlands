package com.inf.farlands.mixin.network;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.inf.farlands.util.network.Commonbounds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

@Mixin(ClientboundCustomPayloadPacket.class)
public class ClientboundCustomPayloadPacketMixin {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;", ordinal = 0))
    private static StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> addGameplayTypes(
            CustomPacketPayload.FallbackProvider<RegistryFriendlyByteBuf> fallback,
            List<CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> types) {
        List<CustomPacketPayload.TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> extended = new ArrayList<>(types);
        for (int i = 0; i < Commonbounds.gameplayBounds.size(); i++) {
            extended.add(Commonbounds.gameplayBounds.get(i));
        }
        return CustomPacketPayload.codec(fallback, extended);
    }

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;", ordinal = 1))
    private static StreamCodec<FriendlyByteBuf, CustomPacketPayload> addConfigTypes(
            CustomPacketPayload.FallbackProvider<FriendlyByteBuf> fallback,
            List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> types) {
        List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> extended = new ArrayList<>(types);
        for (int i = 0; i < Commonbounds.configBounds.size(); i++) {
            extended.add(Commonbounds.configBounds.get(i));
        }
        return CustomPacketPayload.codec(fallback, extended);
    }
}