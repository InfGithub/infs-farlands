package com.inf.farlands.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import java.util.Map;
import java.util.function.Function;

/**
 * 命令树跨 source 类型深拷贝与合并的工具。
 *
 * <p>
 * 移植自 NeoForge 1.21.1 的
 * {@code net.neoforged.neoforge.server.command.CommandHelper}。
 * 它只依赖 Brigadier 类型（{@code CommandNode} / {@code ArgumentBuilder} /
 * {@code SuggestionProvider}），不含任何 Minecraft 依赖，所以可以原样移植。
 *
 * <p>
 * 用途：客户端本地命令树的 source 类型是 {@code CommandSourceStack}，而
 * {@code ClientPacketListener.commands} 的类型是 {@code ClientSuggestionProvider}。
 * 为了让客户端命令出现在补全里，需要把前者深拷贝进后者。两边都用独立的根节点，
 * 避免 {@code redirect} 被用来跨树跳转。
 */
public final class CommandHelper {

    private CommandHelper() {
    }

    /**
     * 深拷贝 sourceNode 的子节点并挂到 resultNode 上，同时记录原节点到副本的映射。
     *
     * @param canUse                   用于判断玩家是否有权使用某条命令
     * @param execute                  替换原命令执行体的占位实现
     * @param sourceToResultSuggestion 建议提供者的类型转换函数
     */
    public static <S, T> void mergeCommandNode(CommandNode<S> sourceNode, CommandNode<T> resultNode,
            Map<CommandNode<S>, CommandNode<T>> sourceToResult, S canUse, Command<T> execute,
            Function<SuggestionProvider<S>, SuggestionProvider<T>> sourceToResultSuggestion) {
        sourceToResult.put(sourceNode, resultNode);
        for (CommandNode<S> sourceChild : sourceNode.getChildren()) {
            if (sourceChild.canUse(canUse)) {
                resultNode.addChild(toResult(sourceChild, sourceToResult, canUse, execute, sourceToResultSuggestion));
            }
        }
    }

    private static <S, T> CommandNode<T> toResult(CommandNode<S> sourceNode,
            Map<CommandNode<S>, CommandNode<T>> sourceToResult, S canUse, Command<T> execute,
            Function<SuggestionProvider<S>, SuggestionProvider<T>> sourceToResultSuggestion) {
        if (sourceToResult.containsKey(sourceNode)) {
            return sourceToResult.get(sourceNode);
        }

        ArgumentBuilder<T, ?> resultBuilder;
        if (sourceNode instanceof ArgumentCommandNode) {
            ArgumentCommandNode<S, ?> sourceArgument = (ArgumentCommandNode<S, ?>) sourceNode;
            RequiredArgumentBuilder<T, ?> resultArgumentBuilder = RequiredArgumentBuilder
                    .argument(sourceArgument.getName(), sourceArgument.getType());
            if (sourceArgument.getCustomSuggestions() != null) {
                resultArgumentBuilder.suggests(sourceToResultSuggestion.apply(sourceArgument.getCustomSuggestions()));
            }
            resultBuilder = resultArgumentBuilder;
        } else if (sourceNode instanceof LiteralCommandNode) {
            LiteralCommandNode<S> sourceLiteral = (LiteralCommandNode<S>) sourceNode;
            resultBuilder = LiteralArgumentBuilder.literal(sourceLiteral.getLiteral());
        } else if (sourceNode instanceof RootCommandNode) {
            CommandNode<T> resultNode = new RootCommandNode<>();
            mergeCommandNode(sourceNode, resultNode, sourceToResult, canUse, execute, sourceToResultSuggestion);
            return resultNode;
        } else {
            throw new IllegalStateException("Node type " + sourceNode + " is not a standard node type");
        }

        if (sourceNode.getCommand() != null) {
            resultBuilder.executes(execute);
        }

        if (sourceNode.getRedirect() != null) {
            resultBuilder.redirect(toResult(sourceNode.getRedirect(), sourceToResult, canUse, execute,
                    sourceToResultSuggestion));
        }

        CommandNode<T> resultNode = resultBuilder.build();
        mergeCommandNode(sourceNode, resultNode, sourceToResult, canUse, execute, sourceToResultSuggestion);
        return resultNode;
    }
}
