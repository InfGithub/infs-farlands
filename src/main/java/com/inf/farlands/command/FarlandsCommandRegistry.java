package com.inf.farlands.command;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 命令注册回调表。本 mod 不使用 Fabric API，没有 {@code CommandRegistrationCallback}，
 * 因此自建一个最小等价物：mod 初始化时登记监听器，{@code Commands} 每次重建时统一 fire。
 *
 * <p>
 * 为什么挂在 {@code Commands} 构造器上就够：原版全部命令都在那个构造器里注册，而
 * {@code ReloadableServerResources} 每次重建（含数据包 reload）都会新建 {@code Commands}，
 * 所以监听器会自动跟随重建。这也是 NeoForge 那个事件的做法。
 *
 * <p>
 * 监听器抛出的异常**不兜**，直接向外传播，与 NeoForge 的事件总线一致：命令写错应当在启动期
 * 响亮失败，而不是变成静默不生效的死命令。注意 {@code Bootstrap.validate()} 也会构造
 * {@code Commands}（用 {@code VanillaRegistries.createLookup()} 的 context），所以监听器在
 * 启动校验期也会被调用一次——它必须是纯的，不能碰 ServerLevel、玩家或世界状态。
 */
public final class FarlandsCommandRegistry {

    private static final List<Consumer<CommandRegistrationEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private FarlandsCommandRegistry() {
    }

    /** 登记一个监听器。应在 mod 初始化阶段调用一次。 */
    public static void register(Consumer<CommandRegistrationEvent> listener) {
        LISTENERS.add(listener);
    }

    /** 服务端入口，由 {@code CommandsMixin} 在 {@code Commands} 构造器末尾调用。 */
    public static void fireServer(CommandDispatcher<CommandSourceStack> dispatcher,
            Commands.CommandSelection commandSelection, CommandBuildContext buildContext) {
        fire(new CommandRegistrationEvent(dispatcher, commandSelection, buildContext));
    }

    /** 客户端入口，由客户端本地命令处理器在拿到服务端命令树后调用。 */
    public static void fireClient(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext) {
        fire(new CommandRegistrationEvent(dispatcher, null, buildContext));
    }

    private static void fire(CommandRegistrationEvent event) {
        for (Consumer<CommandRegistrationEvent> listener : LISTENERS) {
            listener.accept(event);
        }
    }
}
