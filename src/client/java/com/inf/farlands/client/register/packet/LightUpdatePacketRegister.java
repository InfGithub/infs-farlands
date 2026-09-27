package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.mixin.render.LevelRendererAccessor;
import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.expand.y.LightUpdatePacket;

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
                LightUpdatePacket.TYPE,
                (payload, context) -> {
                    if (Minecraft.getInstance().level == null) {
                        return;
                    }
                    if (!Minecraft.getInstance().level.dimension().equals(payload.dimension())) {
                        return;
                    }
                    LevelLightEngine le = Minecraft.getInstance().level.getLightEngine();
                    for (LightUpdatePacket.SectionLight e : payload.sky()) {
                        applyLight(le, LightLayer.SKY, payload.chunkX(), payload.chunkZ(), e);
                    }
                    for (LightUpdatePacket.SectionLight e : payload.block()) {
                        applyLight(le, LightLayer.BLOCK, payload.chunkX(), payload.chunkZ(), e);
                    }
                });
    }

    /** 光照增量应用：data=null → 清空该 section 层，getLightValue 恢复搜索；非 null → 覆盖。 */
    private static void applyLight(LevelLightEngine le, LightLayer layer, int cx, int cz,
            LightUpdatePacket.SectionLight e) {
        SectionPos spos = SectionPos.of(cx, e.sectionY(), cz);
        net.minecraft.world.level.chunk.DataLayer incoming = LightUpdatePacket.decodeSectionLight(e.data());
        // 广播那一条会无视差异发整个窗口（为空壳先发的管线兜底），重复收到同一段是常态。与客户端已有的
        // 层比较，一致就整个跳过：既不做覆盖，也不标脏。全零层与 null 都算「无数据」。
        if (sameLayer(le.getLayerListener(layer).getDataLayerData(spos), incoming)) {
            return;
        }
        le.queueSectionData(layer, spos, incoming);
        Minecraft mc = Minecraft.getInstance();
        // viewArea 必须判：LevelRenderer.setLevel(null) 在卸载关卡时把它置空并释放缓冲，而
        // setSectionDirty 自己不判空。关服收尾在途的光照增量落到这里就会 NPE，实测发生在本世界四个
        // 维度全部存档完成之后的一瞬。原版 ClientChunkCache.onLightUpdate 有同一个洞。
        if (mc.levelRenderer != null
                && ((LevelRendererAccessor) mc.levelRenderer).farlands$viewArea() != null) {
            mc.levelRenderer.setSectionDirty(cx, e.sectionY(), cz);
        }
    }

    /** 两层是否等价：null 与全零层都算「无数据」，其余比字节。 */
    private static boolean sameLayer(net.minecraft.world.level.chunk.DataLayer current,
            net.minecraft.world.level.chunk.DataLayer incoming) {
        boolean currentEmpty = current == null || current.isEmpty();
        boolean incomingEmpty = incoming == null || incoming.isEmpty();
        if (currentEmpty || incomingEmpty) {
            return currentEmpty && incomingEmpty;
        }
        return java.util.Arrays.equals(current.getData(), incoming.getData());
    }
}
