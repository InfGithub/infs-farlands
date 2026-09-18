package com.inf.farlands.light;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * 光照数据包载荷，替代 vanilla 的 {@code ClientboundLightUpdatePacketData}。
 *
 * <p>
 * 每层按绝对 section Y 编码，编码为 VarInt + 2048 字节数组，而非 vanilla 的
 * {@code getMinLightSection() + index} 方案。
 */
public class FarLandsLightPacketData {

    final Int2ObjectMap<byte[]> skyLayers; // sectionY -> 编码后的 DataLayer 字节
    final Int2ObjectMap<byte[]> blockLayers;

    /** Server-side: constructed by {@link FarLandsLightEngine#buildLightPacket}. */
    FarLandsLightPacketData(Int2ObjectMap<byte[]> sky, Int2ObjectMap<byte[]> block) {
        this.skyLayers = sky;
        this.blockLayers = block;
    }

    /** Client-side: decode from buffer. */
    private FarLandsLightPacketData(FriendlyByteBuf buf) {
        skyLayers = readLayerMap(buf);
        blockLayers = readLayerMap(buf);
    }

    // 线上格式

    /**
     * 格式：VarInt 数量 -> 每项：VarInt sectionY + byte[2048]。
     */
    public void write(FriendlyByteBuf buf) {
        writeLayerMap(buf, skyLayers);
        writeLayerMap(buf, blockLayers);
    }

    public static FarLandsLightPacketData read(FriendlyByteBuf buf, int cx, int cz) {
        return new FarLandsLightPacketData(buf);
    }

    private static void writeLayerMap(FriendlyByteBuf buf, Int2ObjectMap<byte[]> map) {
        // 按 sectionY 升序，保证确定性顺序
        List<Integer> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.naturalOrder());
        buf.writeVarInt(keys.size());
        for (int sy : keys) {
            buf.writeVarInt(sy);
            buf.writeByteArray(map.get(sy));
        }
    }

    private static Int2ObjectMap<byte[]> readLayerMap(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Int2ObjectMap<byte[]> map = new Int2ObjectOpenHashMap<>(count);
        for (int i = 0; i < count; i++) {
            int sy = buf.readVarInt();
            byte[] data = buf.readByteArray();
            map.put(sy, data);
        }
        return map;
    }

    // 客户端应用

    /**
     * 把收到的光照数据应用到客户端引擎。客户端包处理器解码后调用。
     */
    public void apply(FarLandsLightEngine engine, int cx, int cz) {
        for (var e : skyLayers.int2ObjectEntrySet()) {
            int sy = e.getIntKey();
            engine.queueSectionData(LightLayer.SKY,
                    SectionPos.of(cx, sy, cz), new DataLayer(e.getValue()));
        }
        for (var e : blockLayers.int2ObjectEntrySet()) {
            int sy = e.getIntKey();
            engine.queueSectionData(LightLayer.BLOCK,
                    SectionPos.of(cx, sy, cz), new DataLayer(e.getValue()));
        }
    }
}
