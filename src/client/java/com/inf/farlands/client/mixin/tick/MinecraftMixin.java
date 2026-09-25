package com.inf.farlands.client.mixin.tick;

import com.inf.farlands.client.FarlandsClientTick;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 tick 末尾入口，与服务端那条 tick 钩子对称。
 *
 * Minecraft.tick 每游戏 tick 恰一次，第一句就自增 clientTickCount，所以 RETURN 处拿到的就是
 * 当前 tick 号。入口签名与服务端那条一致取 int，故此处收窄。回收逻辑在
 * {@link FarlandsClientTick}，本类只做转发。
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    private long clientTickCount;

    @Inject(method = "tick", at = @At("RETURN"))
    private void farlands$clientTickEnd(CallbackInfo ci) {
        FarlandsClientTick.atEnd((Minecraft) (Object) this, (int) clientTickCount);
    }
}
