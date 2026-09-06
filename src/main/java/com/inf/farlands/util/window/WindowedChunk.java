package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;
import java.util.Map;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 窗口系统一等公民接口，由 ChunkAccessMixin 注入到 ChunkAccess。
 */
public interface WindowedChunk {

    /** 中心下方半径，下界 = center - N，N = Config.verticalSimulationDistance。 */
    default int windowHalfBelow() {
        return FarlandsConfig.verticalSimulationDistance;
    }

    /** 中心上方半径，上界 = center + N。对称。 */
    default int windowHalfAbove() {
        return FarlandsConfig.verticalSimulationDistance;
    }

    /** 重建窗口视图为精确的 [sectionYMin, sectionYMax]。 */
    void buildWindow(int sectionYMin, int sectionYMax);

    /** 窗口滑到以 centerSectionY 为中心，对称 ±N。 */
    default void moveWindowTo(int centerSectionY) {
        buildWindow(centerSectionY - windowHalfBelow(), centerSectionY + windowHalfAbove());
    }

    /** 确保 sectionY 可见：窗口内不动，窗口外将窗口滑到该点。 */
    default void expandWindowTo(int sectionY) {
        if (sectionY < getWindowMinY() || sectionY > getWindowMaxY()) {
            moveWindowTo(sectionY);
        }
    }

    int getWindowMinY();

    int getWindowMaxY();

    int windowSectionYFromIndex(int index);

    int windowSectionIndexFromY(int sectionY);

    Map<Integer, LevelChunkSection> windowedAllSections();

    /** chunk 的真实 LevelHeightAccessor，维度范围非窗口感知 */
    LevelHeightAccessor levelHeightAccessor();

    // 客户端持有边界

    default int lastPacketMinY() {
        return Integer.MIN_VALUE;
    }

    default int lastPacketMaxY() {
        return Integer.MIN_VALUE;
    }

    default void setLastPacketWindow(int minY, int maxY) {
    }

    // per-section 脏标记，供 fsa 序列化引擎使用

    default void markSectionDirty(int sectionY) {
    }

    default boolean isSectionDirty(int sectionY) {
        return false;
    }

    default void clearSectionDirty(int sectionY) {
    }

    // 增量扫描

    default void addActiveSection(int sectionY) {
    }

    default void removeActiveSection(int sectionY) {
    }

    default void forEachOutsideWindows(int margin, java.util.function.IntConsumer consumer) {
    }
}
