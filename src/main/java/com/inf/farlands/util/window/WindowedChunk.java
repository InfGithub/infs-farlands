package com.inf.farlands.util.window;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.world.WorldBounds;
import java.util.Map;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/**
 * 窗口系统一等公民接口，由 ChunkAccessMixin 注入到 ChunkAccess。
 */
public interface WindowedChunk {

    /** chunk 的 section 容器工厂（26.1.2 构造器参数，mixin 存字段），跨类建 section 用。 */
    PalettedContainerFactory containerFactory();

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

    /**
     * 窗口滑到以 centerSectionY 为中心，对称 ±N，界夹到可表示段范围后再释放窗口加余量之外的段。
     *
     * <p>窗口只是视图，段容器是 allSections。移动后不释放旧段，容器就是历次窗口的并集，
     * 随竖直移动单调增长；释放并入移动这一步，容器便恒等于窗口加余量。
     *
     * <p>夹取必须先 long 化：center 接近 int 顶端时 center + N 自身就回绕，回绕后的值再取 min
     * 会得到错误的下界。不夹取时中心 + N 会越过可表示段上界，造出方块基址 sy 左移 4 位已经
     * 回绕的段，光照播种写该段即越界。
     */
    default void moveWindowTo(int centerSectionY) {
        int sectionYMin = (int) Math.max((long) centerSectionY - windowHalfBelow(),
                (long) WorldBounds.MIN_SECTION);
        int sectionYMax = (int) Math.min((long) centerSectionY + windowHalfAbove(),
                (long) WorldBounds.MAX_SECTION);
        buildWindow(sectionYMin, sectionYMax);
        releaseSectionsOutsideWindow(FarlandsConfig.sectionCleanupMargin);
    }

    /**
     * 释放当前窗口加 margin 之外的段及其伴随状态。仅客户端实现：服务端的段释放必须先落盘，
     * 归 fsa 生命周期的脏段预算与提交顺序管。
     */
    default void releaseSectionsOutsideWindow(int margin) {
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
