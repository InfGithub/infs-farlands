package com.inf.farlands.client.register.command;

import com.inf.farlands.command.CommandRegistrationEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 客户端示例命令：{@code /farlands client fps}，打印当前帧率。
 *
 * <p>
 * 它与服务端的 {@code /farlands tick} 成对，用来验证客户端本地命令的完整链路：本地注册、
 * 本地执行（不发服务端）、以及经 {@link com.inf.farlands.client.command.ClientCommandSourceStack}
 * 的 {@code sendSuccess} 覆写回显到本地玩家。
 */
public final class FarlandsFpsCommand {

    private FarlandsFpsCommand() {
    }

    public static void register(CommandRegistrationEvent event) {
        event.getDispatcher().register(
                Commands.literal("farlands")
                        .then(Commands.literal("client")
                                .then(Commands.literal("fps")
                                        .executes(context -> {
                                            int fps = Minecraft.getInstance().getFps();
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("fps=" + fps), false);
                                            return 1;
                                        }))));
    }
}
