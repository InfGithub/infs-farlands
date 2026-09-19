package com.inf.farlands.client.command;

import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.command.FarlandsCommandRegistry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;

/**
 * 客户端本地命令处理器。
 *
 * <p>
 * 原版客户端的 {@code ClientPacketListener.commands} 类型是
 * {@code CommandDispatcher<ClientSuggestionProvider>}，而且每次收到服务端下发的命令树都会**整个替换**
 * 它（{@code handleCommands}），所以客户端命令必须在那之后补挂。这里维护一棵独立的本地命令树，
 * 并把它的子节点深拷贝合并进服务端树——只为让本地命令出现在补全里。
 *
 * <p>
 * 结构移植自 NeoForge 1.21.1 的 {@code ClientCommandHandler}，但有三处 26.1.2 适配：
 * <ul>
 * <li>服务端树的泛型是 {@code ClientSuggestionProvider}（由
 * {@code ClientPacketListener.COMMAND_NODE_BUILDER} 决定），不是 1.21.1 的
 * {@code SharedSuggestionProvider}；</li>
 * <li>{@code SuggestionProviders.safelySwap} 已被移除，用 {@code getName} +
 * {@code getProvider} 复刻
 * （{@link #safelySwap}）；</li>
 * <li>拦截点由 {@code LocalPlayer.sendCommand} 改到
 * {@code ClientPacketListener.sendCommand}（前者在 26.1.2 已不存在）。</li>
 * </ul>
 */
public final class ClientCommandHandler {

    /** {@code ask_server} 的 ID；26.1.2 把它作为私有常量，这里自备一份用于复刻 safelySwap。 */
    private static final Identifier ASK_SERVER_ID = Identifier.withDefaultNamespace("ask_server");

    /** 客户端本地命令树。source 类型是 {@link CommandSourceStack}，与客户端收到的命令树不同。 */
    private static CommandDispatcher<CommandSourceStack> commands;

    private ClientCommandHandler() {
    }

    public static CommandDispatcher<CommandSourceStack> getDispatcher() {
        return commands;
    }

    public static ClientCommandSourceStack getSource() {
        LocalPlayer player = Minecraft.getInstance().player;
        return new ClientCommandSourceStack(player);
    }

    /**
     * 重建本地命令树并把子节点合并进服务端下发的命令树，返回合并后的树。
     *
     * <p>
     * 两棵树各自深拷贝到新的根节点：客户端命令不能带 redirect 跳进服务端命令，反之亦然。
     * 合并进服务端命令的节点用 {@code context -> 0} 占位执行体——它们只为了补全显示，
     * 真正的执行走本地 dispatcher（{@link #runCommand}）。
     */
    public static CommandDispatcher<ClientSuggestionProvider> mergeServerCommands(
            CommandDispatcher<ClientSuggestionProvider> serverCommands, CommandBuildContext buildContext) {
        CommandDispatcher<CommandSourceStack> registered = new CommandDispatcher<>();
        FarlandsCommandRegistry.fireClient(registered, buildContext);

        commands = new CommandDispatcher<>();
        copy(registered.getRoot(), commands.getRoot());

        CommandDispatcher<ClientSuggestionProvider> merged = new CommandDispatcher<>();
        copy(serverCommands.getRoot(), merged.getRoot());

        CommandHelper.mergeCommandNode(commands.getRoot(), merged.getRoot(), new IdentityHashMap<>(),
                getSource(), context -> 0, ClientCommandHandler::adaptSuggestion);

        return merged;
    }

    /**
     * 建议提供者的跨 source 类型适配。本地没有已知 ID 的建议器（safelySwap 判定为 ASK_SERVER）
     * 换成"本地解析"：客户端自己就能算本地命令的补全，不需要问服务端。
     */
    @SuppressWarnings("unchecked")
    private static SuggestionProvider<ClientSuggestionProvider> adaptSuggestion(
            SuggestionProvider<CommandSourceStack> suggestion) {
        if (safelySwap(suggestion) == SuggestionProviders.ASK_SERVER) {
            return (context, builder) -> {
                ClientCommandSourceStack source = getSource();
                StringReader reader = new StringReader(context.getInput());
                if (reader.canRead() && reader.peek() == '/') {
                    reader.skip();
                }
                ParseResults<CommandSourceStack> parse = commands.parse(reader, source);
                return commands.getCompletionSuggestions(parse);
            };
        }
        return (SuggestionProvider<ClientSuggestionProvider>) (SuggestionProvider<?>) suggestion;
    }

    /**
     * 复刻 1.21.1 的 {@code SuggestionProviders.safelySwap}（26.1.2 已删除该方法）。
     *
     * <p>
     * 语义：经 {@code register} 登记过、有已知 ID 的建议器原样返回；其它（没有 ID、无法跨端表达）
     * 降级为 {@link SuggestionProviders#ASK_SERVER}，调用方据此换成"本地解析"。
     *
     * <p>
     * 26.1.2 把包装类 {@code RegisteredSuggestion} 收成了 private，没法 instanceof，只能走
     * {@code getName} + {@code getProvider}。注意 {@code getProvider(getName(x))} 返回的是
     * map 里的
     * 原始 provider，与具名常量不是同一对象，所以不要用 {@code ==} 与具名常量比较。
     */
    private static SuggestionProvider<?> safelySwap(SuggestionProvider<?> provider) {
        Identifier name = SuggestionProviders.getName(provider);
        return name.equals(ASK_SERVER_ID) ? SuggestionProviders.ASK_SERVER
                : SuggestionProviders.getProvider(name);
    }

    /** 深拷贝命令树，redirect 指向副本而不是原节点。 */
    private static <S> void copy(CommandNode<S> source, CommandNode<S> result) {
        Map<CommandNode<S>, CommandNode<S>> newNodes = new IdentityHashMap<>();
        newNodes.put(source, result);
        for (CommandNode<S> child : source.getChildren()) {
            CommandNode<S> childCopy = newNodes.computeIfAbsent(child, node -> {
                CommandNode<S> innerCopy = node.createBuilder().build();
                copy(node, innerCopy);
                return innerCopy;
            });
            result.addChild(childCopy);
        }
    }

    /**
     * 先按本地命令树执行。返回 {@code false} 表示本地不认这条命令，应交给服务端——
     * 这是整个客户端命令链里最不能错的一条路径，否则所有服务端命令都会被吞掉。
     */
    public static boolean runCommand(String command) {
        if (commands == null) {
            return false;
        }
        StringReader reader = new StringReader(command);
        ClientCommandSourceStack source = getSource();
        try {
            commands.execute(reader, source);
        } catch (CommandSyntaxException syntax) {
            if (syntax.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                    || syntax.getType() == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument()) {
                return false;
            }
            reply(Component.literal("").append(ComponentUtils.fromMessage(syntax.getRawMessage()))
                    .withStyle(ChatFormatting.RED));
        } catch (Exception e) {
            reply(Component.translatable("command.failed").withStyle(ChatFormatting.RED));
            InfsFarlands.LOGGER.error("farlands: client command failed: {}", command, e);
        }
        return true;
    }

    private static void reply(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
