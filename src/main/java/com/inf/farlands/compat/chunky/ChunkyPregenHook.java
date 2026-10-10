package com.inf.farlands.compat.chunky;

import com.inf.farlands.command.CommandRegistrationEvent;
import com.inf.farlands.command.FarlandsCommandRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.popcraft.chunky.platform.FabricSender;

/**
 * Chunky 兼容层的命令部分，是本层两个允许出现 Chunky 类型的普通类之一，另一处是那个 mixin。
 *
 * <p>为什么权限门挂在自己的节点上：Brigadier 合并同名 literal 时，保留下来的既有节点说了算，新节点的
 * 子节点被并进去，而新节点自己的 command 与 requirement 被丢掉。所以我们与 Chunky 谁先注册不能决定门
 * 的归属，门必须挂在自己的 {@code farlands} 字面量上。
 *
 * <p>Chunky 1.5.3 版本绑定，升级即碎：这里用到 {@code FabricSender} 的构造器与
 * {@code hasPermission(String, boolean)}，以及它的权限节点名 {@code chunky.command}。
 */
final class ChunkyPregenHook {

    private ChunkyPregenHook() {
    }

    static void registerCommands() {
        FarlandsCommandRegistry.registerServer(ChunkyPregenHook::fill);
    }

    /**
     * 往 {@code /chunky} 树里挂一棵 {@code farlands} 子树。同名 literal 由 Brigadier 并入既有节点，所以这
     * 是「扩展它的命令树」而不是造第二棵树；节点自带权限门，与注册顺序无关。
     */
    private static void fill(CommandRegistrationEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("chunky").then(Commands.literal("farlands")
                .requires(source -> new FabricSender(source).hasPermission("chunky.command", true))
                .then(Commands.literal("minY").then(Commands.argument("y", IntegerArgumentType.integer())
                        .executes(context -> {
                            int value = IntegerArgumentType.getInteger(context, "y");
                            if (!ChunkyPregen.setMinSection(value)) {
                                context.getSource().sendFailure(Component.translatable(
                                        "commands.infs-farlands.chunky.farlands.minY.range",
                                        ChunkyPregen.maxSection()));
                                return 0;
                            }
                            reply(context, "commands.infs-farlands.chunky.farlands.band",
                                    ChunkyPregen.minSection(), ChunkyPregen.maxSection());
                            return 1;
                        })))
                .then(Commands.literal("maxY").then(Commands.argument("y", IntegerArgumentType.integer())
                        .executes(context -> {
                            int value = IntegerArgumentType.getInteger(context, "y");
                            if (!ChunkyPregen.setMaxSection(value)) {
                                context.getSource().sendFailure(Component.translatable(
                                        "commands.infs-farlands.chunky.farlands.maxY.range",
                                        ChunkyPregen.minSection()));
                                return 0;
                            }
                            reply(context, "commands.infs-farlands.chunky.farlands.band",
                                    ChunkyPregen.minSection(), ChunkyPregen.maxSection());
                            return 1;
                        })))
                .executes(context -> {
                    reply(context, "commands.infs-farlands.chunky.farlands.band",
                            ChunkyPregen.minSection(), ChunkyPregen.maxSection());
                    return 1;
                })));
    }

    /** 三条成功回复共用一条文案，两个数值是当前预生成范围的两端。 */
    private static void reply(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendSuccess(() -> Component.translatable(key, args), false);
    }
}
