package com.inf.farlands.client.compat.sodium;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sodium 侧的 section 集合同步。
 *
 * <p>
 * Sodium 的渲染 section 集合由 XZ 的 ChunkTracker 驱动，竖直方向的窗口滑动不产生事件，于是窗口移动后
 * 新 section 不进集合、滑出的旧 section 不清理。本类在每帧窗口拉正之后按 chunk 比对上次窗口，窗口变化
 * 时交给实现类通知 Sodium。
 *
 * <p>
 * 本类不得出现任何 Sodium 类型，它们只允许出现在 SodiumWindowSyncImpl 的方法体里。这样没装 Sodium 的
 * 客户端在加载与校验本类时不需要解析 Sodium 的类，实现类也只在门控为真时才被调用与加载。
 *
 * <p>
 * 数据侧的窗口拉正与滑出丢弃由 WindowedChunk.moveWindowTo 负责，本类不重复做。
 */
public final class SodiumWindowSync {

    private SodiumWindowSync() {
    }

    /** 是否装了 Sodium，null 表示尚未查过。 */
    private static Boolean loaded;

    /** 同步失败后停用，只影响 Sodium 侧的集合跟随，不影响数据侧。 */
    private static boolean disabled;

    private static boolean warned;

    private static ClientLevel lastLevel;

    /** chunk 到上次窗口下界。键用 chunk 自持的 ChunkPos 实例，不新建。 */
    private static final Map<ChunkPos, Integer> LAST_WINDOW = new HashMap<>();

    /** 本帧见到的 chunk，32-32 编码，用于帧末丢掉已卸载的记录。 */
    private static final LongOpenHashSet VISITED = new LongOpenHashSet(8192);

    /** 帧起点：换世界时清记录，返回本帧是否做 Sodium 侧同步。 */
    public static boolean beginFrame(ClientLevel level) {
        if (level != lastLevel) {
            lastLevel = level;
            LAST_WINDOW.clear();
        }
        VISITED.clear();
        return active();
    }

    /** 窗口拉正之后调用，只在该 chunk 的窗口变化时通知 Sodium。 */
    public static void onChunk(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        VISITED.add(encode(pos));
        int minY = ((WindowedChunk) chunk).getWindowMinY();
        Integer last = LAST_WINDOW.put(pos, minY);
        if (last != null && last == minY) {
            return;
        }
        try {
            SodiumWindowSyncImpl.onWindowChanged(chunk, last == null ? Integer.MIN_VALUE : last, minY);
        } catch (Throwable t) {
            disabled = true;
            if (!warned) {
                warned = true;
                InfsFarlands.LOGGER.warn("Sodium window sync disabled", t);
            }
        }
    }

    /** 帧末：丢掉已卸载 chunk 的记录，防记录随会话增长。 */
    public static void endFrame() {
        LAST_WINDOW.keySet().removeIf(pos -> !VISITED.contains(encode(pos)));
    }

    private static boolean active() {
        if (disabled) {
            return false;
        }
        Boolean value = loaded;
        if (value == null) {
            value = FabricLoader.getInstance().isModLoaded("sodium");
            loaded = value;
        }
        return value;
    }

    /** vanilla 32-32 无损编码，与 ChunkPos.pack 同布局；手写以绕开侧信道反查表。 */
    private static long encode(ChunkPos pos) {
        return (long) pos.x() & 4294967295L | ((long) pos.z() & 4294967295L) << 32;
    }
}
