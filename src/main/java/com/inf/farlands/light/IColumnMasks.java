package com.inf.farlands.light;

/**
 * 列级非空掩码访问器，由 LevelChunkSectionMixin 实现。
 *
 * 掩码：short[256]，每列 x+z*16 一个 16-bit 行掩码，第 y 位表示该行有非空方块。
 * fillFrom 的 findLowestSourceYAll 查掩码跳过全空列/空气行 免逐格
 * getBlockState 的 palette 查找 塔顶露天世界 fillFrom 从 14 万格/chunk
 * 降到只有方块行，JFR farlands-gen findLowestSourceYAll 占 80%。
 */
public interface IColumnMasks {
    /** 返回该 section 的列掩码，懒创建：首次调用或 read 后重建，扫 4096 格。 */
    short[] farlands$ensureColumnMasks();

    /**
     * N1：返回当前掩码，未初始化时为 null 且不 rebuild 传播 getState 快路径用：
     * fill 后 section 掩码有效，setBlockState 增量维护 -> 空气行免 palette 查找；
     * 懒创建空 section 掩码 null -> 走 palette，防止 ensure 的 rebuild 扫 4096 格风暴。
     */
    short[] farlands$columnMasksIfReady();
}
