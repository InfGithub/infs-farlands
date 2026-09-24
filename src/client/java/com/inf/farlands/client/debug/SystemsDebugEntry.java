package com.inf.farlands.client.debug;

import java.util.List;
import java.util.Map;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.client.network.ClientSystems;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.LevelSelection;
import com.inf.farlands.terrain.registry.SystemsData.SystemSelection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 调试界面上的当前 level 四族系统。
 *
 * <p>只读客户端收到的那份，服务端在玩家进服或换维度后下发一次。查不到就明写未收到，不做本地回退，
 * 于是包没发、维度对不上、handler 没登记都会在界面上显形。
 *
 * <p>参数文字直接取 NBT 标签的 SNBT 形式，显示层因此不需要第二份参数类型表。代价是文字带类型后缀，
 * seed 显示成 0L、scaleX 显示成 16.0d。
 */
public class SystemsDebugEntry implements DebugScreenEntry {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(InfsFarlands.MOD_ID, "systems");

    @Override
    public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk,
            LevelChunk serverChunk) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelSelection selection = ClientSystems.get(level.dimension().identifier());
        if (selection == null) {
            displayer.addToGroup(ID, "systems: not received");
            return;
        }
        displayer.addToGroup(ID, List.of(
                line("terrain", selection.terrain()),
                line("biome", selection.biome()),
                line("surface", selection.surface()),
                line("carver", selection.carver())));
    }

    private static String line(String family, SystemSelection selection) {
        StringBuilder out = new StringBuilder(family).append(": ").append(selection.id());
        for (Map.Entry<String, Arg> entry : selection.args().entrySet()) {
            out.append(' ').append(entry.getKey()).append('=')
                    .append(entry.getValue().value().getValue());
        }
        return out.toString();
    }
}
