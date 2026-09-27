package com.inf.farlands.client.mixin.tick;

import com.inf.farlands.client.ClientWorldState;
import com.inf.farlands.client.FarlandsClientTick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

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

    /**
     * 卸关卡后的进程级清理。三参 disconnect 是唯一出口：disconnectFromWorld、disconnectWithSavingScreen、
     * disconnectWithProgressScreen 与两参 disconnect 最终都汇入它。RETURN 处集成服务端线程已死、level 已置空、
     * updateLevelInEngines(null) 已跑、player 已空，旧世界的打包键没有活持有者。
     */
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("RETURN"))
    private void farlands$clearOnDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound,
            CallbackInfo ci) {
        ClientWorldState.clearOnLevelDrop();
    }

    /**
     * 服务端要求重入配置阶段时的那条卸关卡路径。它不经过 disconnect，也不停集成服务端，所以只清客户端这一侧。
     */
    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void farlands$clearOnLevelClear(Screen screen, CallbackInfo ci) {
        ClientWorldState.clearOnLevelDrop();
    }
}
