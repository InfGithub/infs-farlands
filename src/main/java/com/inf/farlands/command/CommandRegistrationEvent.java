package com.inf.farlands.command;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 命令注册事件，形状对齐 NeoForge 的 {@code RegisterCommandsEvent}。
 *
 * <p>
 * 服务端由 {@code CommandsMixin} 在 {@code Commands} 构造器末尾触发，携带该次构造用的
 * {@link Commands.CommandSelection} 与 {@link CommandBuildContext}；客户端由本地命令处理器触发，
 * 此时 {@link #getCommandSelection()} 返回 {@code null}（客户端的命令树不区分
 * DEDICATED/INTEGRATED）。
 */
public final class CommandRegistrationEvent {

    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final Commands.CommandSelection commandSelection;
    private final CommandBuildContext buildContext;

    public CommandRegistrationEvent(CommandDispatcher<CommandSourceStack> dispatcher,
            Commands.CommandSelection commandSelection, CommandBuildContext buildContext) {
        this.dispatcher = dispatcher;
        this.commandSelection = commandSelection;
        this.buildContext = buildContext;
    }

    /** 本次要填充的命令调度器。 */
    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return this.dispatcher;
    }

    /** 服务端为 DEDICATED 或 INTEGRATED；客户端为 {@code null}。 */
    public Commands.CommandSelection getCommandSelection() {
        return this.commandSelection;
    }

    /** 用于构建需要查询注册表的命令（26.1.2 的 {@code CommandBuildContext}）。 */
    public CommandBuildContext getBuildContext() {
        return this.buildContext;
    }
}
