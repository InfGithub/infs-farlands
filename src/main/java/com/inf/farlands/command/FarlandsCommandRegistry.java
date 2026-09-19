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
 * <b>服务端与客户端必须分开登记。</b>两边的 dispatcher 虽然类型相同
 * （{@code CommandDispatcher<CommandSourceStack>}），但 source 语义完全不同：服务端的 source
 * 背后是 {@code ServerPlayer}，客户端本地命令的 source 是 {@code ClientCommandSourceStack}
 * （里面装 {@code LocalPlayer}，{@code getLevel()}/{@code getServer()} 明确不支持）。把一个方向
 * 的命令注册进另一个方向会造成两类故障：
 * <ul>
 * <li>服务端命令落进客户端本地树 → 本地匹配成功并以 {@code ClientCommandSourceStack} 执行 →
 * {@code getPlayerOrException()} 抛 {@code NOT_PLAYER}，命令再也透传不到服务端；</li>
 * <li>客户端命令落进服务端树 → 专用服务器上执行它会引用 {@code Minecraft} 类直接崩。</li>
 * </ul>
 * NeoForge 用两个不同的事件类（{@code RegisterCommandsEvent} /
 * {@code RegisterClientCommandsEvent}）区分，这里用两个登记入口表达同一件事。
 *
 * <p>
 * 监听器抛出的异常**不兜**，直接向外传播，与 NeoForge 的事件总线一致：命令写错应当在启动期
 * 响亮失败，而不是变成静默不生效的死命令。注意 {@code Bootstrap.validate()} 也会构造
 * {@code Commands}（用 {@code VanillaRegistries.createLookup()} 的 context），所以服务端监听器在
 * 启动校验期也会被调用一次——它必须是纯的，不能碰 ServerLevel、玩家或世界状态。
 */
public final class FarlandsCommandRegistry {

    private static final List<Consumer<CommandRegistrationEvent>> SERVER_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<CommandRegistrationEvent>> CLIENT_LISTENERS = new CopyOnWriteArrayList<>();

    private FarlandsCommandRegistry() {
    }

    /** 登记服务端命令监听器。由 {@code CommandsMixin} 在 {@code Commands} 构造器末尾触发。 */
    public static void registerServer(Consumer<CommandRegistrationEvent> listener) {
        SERVER_LISTENERS.add(listener);
    }

    /** 登记客户端本地命令监听器。由客户端本地命令处理器在拿到服务端命令树后触发。 */
    public static void registerClient(Consumer<CommandRegistrationEvent> listener) {
        CLIENT_LISTENERS.add(listener);
    }

    /** 服务端入口，由 {@code CommandsMixin} 在 {@code Commands} 构造器末尾调用。 */
    public static void fireServer(CommandDispatcher<CommandSourceStack> dispatcher,
            Commands.CommandSelection commandSelection, CommandBuildContext buildContext) {
        fire(SERVER_LISTENERS, new CommandRegistrationEvent(dispatcher, commandSelection, buildContext));
    }

    /** 客户端入口，由客户端本地命令处理器在拿到服务端命令树后调用。 */
    public static void fireClient(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext) {
        fire(CLIENT_LISTENERS, new CommandRegistrationEvent(dispatcher, null, buildContext));
    }

    private static void fire(List<Consumer<CommandRegistrationEvent>> listeners, CommandRegistrationEvent event) {
        for (Consumer<CommandRegistrationEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
