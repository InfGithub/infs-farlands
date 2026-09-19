package com.inf.farlands.client.mixin.command;

import com.inf.farlands.client.command.ClientCommandHandler;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端命令的两处接线。
 *
 * <p>
 * 与 {@code expand.y.ClientPacketListenerMixin} 同名但不同包：那个处理窗口与取数，这个只处理命令。
 *
 * <p>
 * {@code commands} 字段是 private 但非 final，所以可以直接 {@code @Shadow} 并写回；
 * {@code registryAccess()} 与 {@code enabledFeatures()} 是 public 方法，用强转后的 self
 * 调用即可，
 * 不需要 shadow。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Shadow
    private CommandDispatcher<ClientSuggestionProvider> commands;

    /**
     * 服务端下发的命令树到达后，把客户端本地命令合并进去。原版这里直接整个替换
     * （{@code this.commands = new CommandDispatcher<>(root)}），替换掉就等于本地命令消失。
     */
    @Inject(method = "handleCommands", at = @At("RETURN"))
    private void farlands$mergeClientCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        this.commands = ClientCommandHandler.mergeServerCommands(this.commands,
                CommandBuildContext.simple(self.registryAccess(), self.enabledFeatures()));
    }

    /**
     * 输入 {@code /xxx} 时先试本地命令；命中就地执行并取消发包，未命中则原样交给服务端。
     */
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void farlands$tryClientCommand(String command, CallbackInfo ci) {
        if (ClientCommandHandler.runCommand(command)) {
            ci.cancel();
        }
    }
}
