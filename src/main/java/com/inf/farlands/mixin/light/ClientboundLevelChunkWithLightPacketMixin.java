package com.inf.farlands.mixin.light;

import com.inf.farlands.light.FarLandsLightEngine;
import com.inf.farlands.light.FarLandsLightPacketData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 {@link FarLandsLightPacketData} 附加到 chunk-with-light 包上，
 * 使所有 section 在一次原子写入中携带光照数据，不只维度范围内那些。
 *
 * <p>26.1.2：三处注入点（LevelChunk+LevelLightEngine+BitSet×2 构造、
 * private void write(RegistryFriendlyByteBuf)、private &lt;init&gt;(RegistryFriendlyByteBuf)）
 * 与 1.21.1 同名同描述符，无需改动。
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public class ClientboundLevelChunkWithLightPacketMixin {

    @Unique
    private FarLandsLightPacketData farlandsLightData;

    /** Server: capture our light data after vanilla construction. */
    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("RETURN"))
    private void onServerConstruct(LevelChunk chunk, LevelLightEngine lightEngine, java.util.BitSet skyLight,
                                   java.util.BitSet blockLight, CallbackInfo ci) {
        if (lightEngine instanceof FarLandsLightEngine fle) {
            farlandsLightData = fle.buildLightPacket(chunk.getPos());
        }
    }

    /** Append our light data after the vanilla write. */
    @Inject(method = "write", at = @At("RETURN"))
    private void onWrite(RegistryFriendlyByteBuf buf, CallbackInfo ci) {
        if (farlandsLightData != null) farlandsLightData.write(buf);
    }

    /** Client: read our light data after vanilla decode. */
    @Inject(method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At("RETURN"))
    private void onClientConstruct(RegistryFriendlyByteBuf buf, CallbackInfo ci) {
        if (buf.readableBytes() > 0) {
            farlandsLightData = FarLandsLightPacketData.read(buf, 0, 0);
        }
    }

    /** Accessor for client handler（客户端经反射读取本 @Unique 字段）。 */
    public FarLandsLightPacketData farlands$getLightData() {
        return farlandsLightData;
    }
}
