package com.inf.farlands.terrain.system.terrain.block.misc.Sierpinski;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 谢尔宾斯基四棱锥，逐块几何系统。
 *
 * <p>顶点在 {@code (0, apexY, 0)}，层数 {@code d = apexY - y}，命中判据是位运算：{@code d - coord}
 * 为偶，且它右移一位后的位是 {@code d} 的子集。d 与两个差都用 long，坐标取到 int 两端也不溢出。
 *
 * <p>顶点 y 由参数给出，默认 {@code (1 << 30) - 1}，即顶点落在 y = 1073741823。层数只依赖 apexY
 * 与 y，因此 apexY 取任意 int 都有定义：顶点段越出可玩段范围时顶点不生成而中下层照常，apexY
 * 低于可玩范围时全空。
 *
 * <p>无状态：{@link BlockSystem#fillBlock} 是纯函数，实例随 level 走、可跨 genPool 线程共享。
 */
public final class SierpinskiPyramidBlockSystem implements BlockSystem {

    /** 顶点 y 的默认值，顶点落在 1073741823。声明在 DEFAULT_PARAMS 之前，避免字段初始化器的非法前向引用。 */
    private static final int DEFAULT_APEX_Y = (1 << 30) - 1;

    /** 声明项：顶点 y。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofInt("apexY").define(DEFAULT_APEX_Y).build());

    private final int apexY;

    public SierpinskiPyramidBlockSystem(SystemArgs args) {
        this.apexY = args.getInt("apexY");
    }

    @Override
    public BlockState fillBlock(int x, int y, int z) {
        return isInSet(x, y, z) ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    /** diff = d - coord 偶且 diff>>1 的位 in d。 */
    private boolean isInSet(int x, int y, int z) {
        long d = (long) this.apexY - y; // 层数
        if (d < 0) {
            return false; // 顶点上方
        }
        if (d == 0) {
            return x == 0 && z == 0; // 顶点单点
        }
        long dx = d - x;
        if ((dx & 1) != 0) {
            return false;
        }
        if (((dx >> 1) & ~d) != 0) {
            return false;
        }
        long dz = d - z;
        if ((dz & 1) != 0) {
            return false;
        }
        return ((dz >> 1) & ~d) == 0;
    }
}
