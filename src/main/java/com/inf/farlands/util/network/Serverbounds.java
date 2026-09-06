package com.inf.farlands.util.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;

public class Serverbounds {
    public static final List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> gameplayBounds = new ArrayList<>();
    public static final List<TypeAndCodec<? super FriendlyByteBuf, ?>> configBounds = new ArrayList<>();

    public static void registerGameplay(TypeAndCodec<? super RegistryFriendlyByteBuf, ?> codec) {
        gameplayBounds.add(codec);
    }

    public static void registerConfig(TypeAndCodec<? super FriendlyByteBuf, ?> codec) {
        configBounds.add(codec);
    }

    public static void register(TypeAndCodec<? super FriendlyByteBuf, ?> codec) {
        registerGameplay(codec);
        registerConfig(codec);
    }
}
