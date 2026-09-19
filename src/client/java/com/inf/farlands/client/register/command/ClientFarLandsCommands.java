package com.inf.farlands.client.register.command;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.command.CommandRegistrationEvent;
import com.inf.farlands.register.command.FarLandsCommands;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 客户端诊断命令族 {@code /farlands client section ...}：与服务端 FLDUMP 同格式输出客户端侧
 * 的光照层、群系与方块数据，用于对比定位同步断链。
 *
 * <p>
 * 本类引用客户端类 {@link Minecraft}，只在客户端加载；服务端不注册、不加载这些命令。
 *
 * <p>
 * 移植自上一版本（NeoForge 1.21.1），适配点：{@code RegisterClientCommandsEvent} →
 * {@link CommandRegistrationEvent}（客户端路径的 {@code getCommandSelection()} 为
 * null）、
 * {@code InfFarlands} → {@link InfsFarlands}，以及客户端光照引擎的取法——
 * 26.1.2 的 {@code ClientLevel} 没有 {@code getLightEngine()}，改由
 * {@code level.getChunkSource().getLightEngine()} 取（{@code ClientChunkCache}
 * 持有）。
 */
public class ClientFarLandsCommands {

    public static void register(CommandRegistrationEvent event) {
        event.getDispatcher().register(
                Commands.literal("farlands")
                        .then(Commands.literal("client")
                                .then(Commands.literal("section")
                                        .then(Commands.literal("light")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpClient(ctx.getSource()))))
                                        .then(Commands.literal("biome")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpBiomeClient(ctx.getSource()))))
                                        .then(Commands.literal("block")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpBlocksClient(ctx.getSource())))))));
    }

    private static int dumpClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            LevelLightEngine le = mc.level.getChunkSource().getLightEngine();

            InfsFarlands.LOGGER.info("FLDUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpLayer(le, LightLayer.SKY, sec);
            FarLandsCommands.dumpLayer(le, LightLayer.BLOCK, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.light.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("FLDUMP client err", e);
        }
        return 1;
    }

    private static int dumpBiomeClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BIODUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpBiomes(mc.level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.biome.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BIODUMP client err", e);
        }
        return 1;
    }

    /** 方块数据独立命令：/farlands client section block dump。 */
    private static int dumpBlocksClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BLOCKDUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpBlocks(mc.level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.block.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BLOCKDUMP client err", e);
        }
        return 1;
    }
}
