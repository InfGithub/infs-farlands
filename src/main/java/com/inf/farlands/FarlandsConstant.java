package com.inf.farlands;

public class FarlandsConstant {
    public static final int MAX_SECTION = 134217727;
    public static final int MIN_SECTION = ~MAX_SECTION;
    public static final int MAX_BLOCK = 2147483647;
    public static final int MAX_PLAYABLE_BLOCK = MAX_BLOCK - 16;
    public static final int MAX_PLAYABLE_SECTION = MAX_SECTION - 1;

    /**
     * Mineshaft 结构起点 chunk 的绝对安全上界（防 int 溢出，非玩法边界）。
     *
     * <p>
     * Room 起点 west = chunkX×16+2（getBlockX(2)）。最坏走廊链沿 +X 延伸
     * 至 box 端 = west+99（护栏 80 + 走廊段长 20 − 1，XSpan=10 组合可达），
     * 要求 box 端 ≤ MAX_BLOCK−5：chunkX×16+2+99 ≤ 2147483642
     * → chunkX ≤ (MAX_BLOCK−106)/16 = 134217721（向下取整，16 对齐稳定）。
     * 负向链条起点 = west−1 无 XSpan 借位，box 底 = west−95，安全下界
     * = ~正界（−134217722）——int 值域负侧多 1，正负按位反配对。
     */
    public static final int MINESHAFT_LIMIT_CHUNK = 134217721;
}
