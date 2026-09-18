package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.expand.y.FarLandsLightUpdatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 光照增量包客户端 handler：queueSectionData（data=null → 清层）+ 标记渲染重编译。
 *
 * <p>
 * 维度校验：tp 跨维度在途旧包丢弃，防污染新维度。
 */
public class LightUpdatePacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                FarLandsLightUpdatePacket.TYPE,
                (payload, context) -> {
                    if (Minecraft.getInstance().level == null) {
                        return;
                    }
                    if (!Minecraft.getInstance().level.dimension().equals(payload.dimension())) {
                        return;
                    }
                    LevelLightEngine le = Minecraft.getInstance().level.getLightEngine();
                    for (FarLandsLightUpdatePacket.SectionLight e : payload.sky()) {
                        applyLight(le, LightLayer.SKY, payload.chunkX(), payload.chunkZ(), e);
                    }
                    for (FarLandsLightUpdatePacket.SectionLight e : payload.block()) {
                        applyLight(le, LightLayer.BLOCK, payload.chunkX(), payload.chunkZ(), e);
                    }
                });
    }

    /** 光照增量应用：data=null → 清空该 section 层，getLightValue 恢复搜索；非 null → 覆盖。 */
    private static void applyLight(LevelLightEngine le, LightLayer layer, int cx, int cz,
            FarLandsLightUpdatePacket.SectionLight e) {
        le.queueSectionData(layer, SectionPos.of(cx, e.sectionY(), cz),
                FarLandsLightUpdatePacket.decodeSectionLight(e.data()));
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.setSectionDirty(cx, e.sectionY(), cz);
        }
    }
}
