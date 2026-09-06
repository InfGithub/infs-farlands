package com.inf.farlands.client.mixin.debug.tool.clamp;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.inf.farlands.network.debug.tool.clamp.ClampTogglePacket;

/** F3+K 触发钳制模式 toggle。客户端发请求，服务端校验 OP。 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
    private void clampToggle(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (keyEvent.input() == 75) { // F3+K
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.connection.send(new ServerboundCustomPayloadPacket(new ClampTogglePacket()));
                cir.setReturnValue(true);
            }
        }
    }
}