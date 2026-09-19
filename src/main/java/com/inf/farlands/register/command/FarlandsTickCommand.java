package com.inf.farlands.register.command;

import com.inf.farlands.FarlandsTick;
import com.inf.farlands.command.CommandRegistrationEvent;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 服务端示例命令：{@code /farlands tick}，打印引擎 tick 与 mod 缓存的 tick。
 *
 * <p>
 * 两个值刻意放在一起：{@code MinecraftServer.getTickCount()} 是引擎的真实 tick，
 * {@code FarlandsTick.getNow()} 是本 mod 每 200 tick 刷新一次的缓存值。两者拉开差距说明
 * {@code FarlandsTick.atEnd} 没有按预期被调用。
 *
 * <p>
 * 原版已有 {@code /tick} 命令（{@code server/commands/TickCommand}），所以本命令挂在
 * {@code /farlands} 前缀下，避免冲突。未加 {@code requires}，即所有玩家可用；要收紧就在
 * 这里加 {@code .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))}。
 */
public final class FarlandsTickCommand {

    private FarlandsTickCommand() {
    }

    public static void register(CommandRegistrationEvent event) {
        event.getDispatcher().register(
                Commands.literal("farlands")
                        .then(Commands.literal("tick")
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int engineTick = source.getServer().getTickCount();
                                    int cachedTick = FarlandsTick.getNow();
                                    source.sendSuccess(() -> Component.literal(
                                            "tick=" + engineTick + "  FarlandsTick.now=" + cachedTick), false);
                                    return 1;
                                })));
    }
}
