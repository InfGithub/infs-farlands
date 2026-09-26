package com.inf.farlands.client.register.packet;

import com.inf.farlands.client.network.ClientPacketHandlers;
import com.inf.farlands.network.expand.y.ChunkDataPacket;
import com.inf.farlands.util.maps.Common;
import com.inf.farlands.util.window.WindowedChunk;

import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * §5 窗口滑动 section 包的客户端接收链：到达时应用（chunk 未加载则缓存）、以及
 * chunk 加载后的缓存补应用。
 *
 * <p>type 注册在 main 源集的 {@code ChunkDataPacketRegister}——客户端同时跑 main/client
 * 两份 entrypoint，这里再注册一次会让 Commonbounds.gameplayBounds 出现重复 id，
 * CustomPacketPayload.codec 的 toUnmodifiableMap 会抛 Duplicate key。
 *
 * <p>「缓存补应用」的必要性：§5 到达时 chunk 可能尚未加载；直接丢弃会让方块数据
 * 永久缺失（服务端已出队不重发）——表现为空缺 / 双端不同步（客户端空预测下坠 vs
 * 服务端实心）。故缓存 {@code SectionEntry}，由
 * {@code ClientPacketListenerMixin.handleLevelChunkWithLight} RETURN 时补应用。
 *
 * <p>缓存结构在 main 源集 {@link Common}（只存 SectionEntry），本类负责施放——
 * 施放要 ClientLevel / ClientChunkCache，属客户端。
 */
public class ChunkDataPacketRegister {

    public static void registerHandler() {
        ClientPacketHandlers.register(
                ChunkDataPacket.TYPE,
                (payload, context) -> {
                    ClientLevel level = Minecraft.getInstance().level;
                    if (level == null) {
                        return;
                    }
                    // 维度校验：tp 跨维度在途旧包丢弃，防污染新维度
                    if (!level.dimension().equals(payload.dimension())) {
                        return;
                    }
                    int minY = payload.windowMinY();
                    LevelLightEngine le = level.getChunkSource().getLightEngine();
                    for (ChunkDataPacket.SectionEntry e : payload.sections()) {
                        ChunkAccess ca = level.getChunkSource().getChunk(
                                e.chunkX(), e.chunkZ(), ChunkStatus.FULL, false);
                        if (ca instanceof LevelChunk lc) {
                            applySectionData(level, le, lc, e, minY);
                        } else {
                            // chunk 未加载 → 缓存，chunk 加载后由 applyPendingSectionData 补应用
                            Common.cachePendingSectionData(level.dimension(),
                                    e.chunkX(), e.chunkZ(), minY, e);
                        }
                    }
                });
    }

    /**
     * chunk 加载完成后补应用该 chunk 的 §5 缓存，由
     * {@code ClientPacketListenerMixin.handleLevelChunkWithLight} RETURN 调用。
     *
     * <p>注：26.1.2 侧尚无 §5 发送端（窗口差量 + 限量发包未移植），本方法目前只会
     * 在包头先到达、chunk 后加载的乱序场景下命中；发送端接入后即为常规路径。
     */
    public static void applyPendingSectionData(ClientLevel level, int cx, int cz) {
        Common.PendingSections pending = Common.takePendingSectionData(level.dimension(), cx, cz);
        if (pending == null) {
            return;
        }
        ChunkAccess ca = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (!(ca instanceof LevelChunk lc)) {
            return; // 仍未加载；补应用挂在 chunk 加载之后，理论不触发
        }
        LevelLightEngine le = level.getChunkSource().getLightEngine();
        for (ChunkDataPacket.SectionEntry e : pending.entries) {
            applySectionData(level, le, lc, e, pending.minY);
        }
    }

    /** 应用单个 section 条目：数据 + 光照 + 持有边界 + 标脏。 */
    private static void applySectionData(ClientLevel level, LevelLightEngine le, LevelChunk lc,
            ChunkDataPacket.SectionEntry e, int minY) {
        WindowedChunk wc = (WindowedChunk) lc;
        wc.setLastPacketWindow(minY, minY + wc.windowHalfBelow() + wc.windowHalfAbove());
        // 不可变切换：新建 section 整体替换。PalettedContainer.read 的 createOrReuseData
        // 会复用现有 Data 原地改 palette——与渲染编译线程并发读时读到 palette 中间状态
        // → MissingPaletteEntryException CTD，重生窗口跳变场景下触发。新建对象无并发读者。
        LevelChunkSection ns = new LevelChunkSection(wc.containerFactory());
        ns.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(e.sectionData())));
        wc.windowedAllSections().put(e.sectionY(), ns);
        lc.getSection(lc.getSectionIndexFromSectionY(e.sectionY())); // 数组同步，get 内部执行 arr[idx]=s
        // 整体替换段对象绕开了 LevelChunk.setBlockState 的空/非空翻转通知，那条通知是 26.1.2
        // 的 loadedEmptySections 集合唯一的出栈路径。不补发，有内容的段会被 SectionOcclusionGraph
        // 的闸门永久钉成 EMPTY。
        //
        // 只对窗口内的段补发：该集合的进 addEmptySections 与出 dropEmptySections 都只遍历
        // chunk.getSections，即窗口数组，而 ClientChunkCache.Storage.inRange 只判 chunkX/Z、
        // 不判 sectionY。窗口外段若入集合，卸载时那条出栈路径遍历不到它，键只进不出，
        // 集合随会话单调增长，胀到 add 的容量算式回绕即 Index -1 out of bounds。
        // 窗口判定与第 100 行的 put 同步：同一段在窗口内才能入集合。
        LevelChunkSection inWindow = wc.windowedAllSections().get(e.sectionY());
        if (inWindow == ns) {
            level.getChunkSource().onSectionEmptinessChanged(e.chunkX(), e.sectionY(), e.chunkZ(),
                    ns.hasOnlyAir());
        }
        DataLayer bl = ChunkDataPacket.decodeLight(e.blockLight());
        if (bl != null) {
            le.queueSectionData(LightLayer.BLOCK, SectionPos.of(lc.getPos(), e.sectionY()), bl);
        }
        DataLayer sl = ChunkDataPacket.decodeLight(e.skyLight());
        if (sl != null) {
            le.queueSectionData(LightLayer.SKY, SectionPos.of(lc.getPos(), e.sectionY()), sl);
        }
        level.setSectionDirtyWithNeighbors(e.chunkX(), e.sectionY(), e.chunkZ());
    }
}
