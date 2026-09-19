package com.inf.farlands.client.command;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;

/**
 * 客户端命令用的 {@link CommandSourceStack}：基类里凡是解引用 {@code level}/{@code server}
 * 的方法都覆写成客户端数据源，那两者在客户端不存在（构造时传 {@code null}）。
 *
 * <p>
 * 覆写清单是按 26.1.2 的基类实现逐项推导的，**不能照抄 NeoForge 1.21.1**：后者覆写的 12 个方法里
 * 有 5 个（{@code getRecipeNames}/{@code getScoreboard}/{@code getAdvancement}/
 * {@code getRecipeManager}/{@code getUnsidedLevel}）在 26.1.2 已经不存在，而 26.1.2 的
 * {@code enabledFeatures}（读 {@code this.level}）、{@code dispatcher}（读
 * {@code this.getServer()}）、
 * {@code suggestRegistryElements}（读 {@code this.server}）是 1.21.1 没覆写、但必须覆写的新增点。
 *
 * <p>
 * 约束：客户端命令不得调用会读 {@code this.level} 的基类方法（如 {@code withLevel}），
 * 那些方法保留原实现，属"谁用谁炸"。
 */
public class ClientCommandSourceStack extends CommandSourceStack {

    public ClientCommandSourceStack(LocalPlayer player) {
        super(new LocalCommandSource(player), player.position(), player.getRotationVector(), null,
                player.permissions(), player.getName().getString(), player.getDisplayName(), null, player);
    }

    /** 客户端没有服务端对象，明确不支持。 */
    @Override
    public MinecraftServer getServer() {
        throw new UnsupportedOperationException("Attempted to get server in a client command");
    }

    /** 客户端没有服务端世界，明确不支持。 */
    @Override
    public ServerLevel getLevel() {
        throw new UnsupportedOperationException("Attempted to get server level in a client command");
    }

    /** 基类实现会经 broadcastToAdmins 读 level/server，这里直接发给本地玩家，忽略 broadcast。 */
    @Override
    public void sendSuccess(Supplier<Component> message, boolean broadcast) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(message.get());
        }
    }

    @Override
    public Collection<String> getOnlinePlayerNames() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection == null ? List.of()
                : connection.getOnlinePlayers().stream().map(info -> info.getProfile().name()).toList();
    }

    @Override
    public Collection<String> getAllTeams() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? List.of() : level.getScoreboard().getTeamNames();
    }

    @Override
    public Set<ResourceKey<Level>> levels() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection == null ? Set.of() : connection.levels();
    }

    @Override
    public RegistryAccess registryAccess() {
        return Minecraft.getInstance().getConnection().registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return Minecraft.getInstance().getConnection().enabledFeatures();
    }

    /** 客户端提示要基于本地命令树解析，而不是服务端。 */
    @Override
    public CommandDispatcher<CommandSourceStack> dispatcher() {
        return ClientCommandHandler.getDispatcher();
    }

    /** 基类实现读 this.server，这里改走客户端的 registryAccess。 */
    @Override
    public CompletableFuture<Suggestions> suggestRegistryElements(ResourceKey<? extends Registry<?>> key,
            SharedSuggestionProvider.ElementSuggestionType elements, SuggestionsBuilder builder,
            CommandContext<?> context) {
        return this.registryAccess().lookup(key).map(registry -> {
            this.suggestRegistryElements((HolderLookup<?>) registry, elements, builder);
            return builder.buildFuture();
        }).orElseGet(Suggestions::empty);
    }

    /**
     * 客户端命令的 {@link CommandSource} 载体。
     *
     * <p>
     * 26.1.2 的实体不再实现 {@code CommandSource}（javap 确认），所以不能像 NeoForge 1.21.1 那样
     * 直接把 {@code LocalPlayer} 当 source 传进去，必须自备一个。
     */
    private record LocalCommandSource(LocalPlayer player) implements CommandSource {

        @Override
        public void sendSystemMessage(Component message) {
            this.player.sendSystemMessage(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
