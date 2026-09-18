package com.inf.farlands.mixin.tick;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.inf.farlands.FarlandsTick;

import net.minecraft.server.MinecraftServer;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Shadow
    private int tickCount;

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void onServerTickEnd(CallbackInfo ci) {
        FarlandsTick.atEnd((MinecraftServer) (Object) this, tickCount);
    }
}
