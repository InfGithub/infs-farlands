package com.inf.farlands.terrain.system.terrain.block.overworld.Infdev;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Infdev 20100226 无限世界地形：y 小于 0 全基岩，0 到高度之间是草方块，高度之上是空气。
 * 没有洞穴、水与树。
 *
 * <p>
 * 高度是 4x4 周期的微丘，值域 64 到 68。判定只依赖坐标对 4 取模的余数，与种子无关。
 *
 * <p>
 * 取模必须用 {@link Math#floorMod}，不能用 {@code %}：原始实现的两个余数由 chunkX 乘 4 起算，
 * 恒为 4 的倍数，所以余数恒非负；本实现直接拿绝对坐标，负坐标下 {@code %} 会给出负余数，判定链
 * 落进哪个分支都不对，微丘图案随之错乱。
 *
 * <p>
 * 无状态：{@link BlockSystem#fillBlock} 是纯函数，实例随 level 走、可跨 genPool 线程共享。
 */
public final class Infdev20100226BlockSystem implements BlockSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    /** 统一构造签名，本系统不读任何参数。 */
    public Infdev20100226BlockSystem(SystemArgs args) {
    }

    @Override
    public BlockState fillBlock(int x, int y, int z) {
        if (y < 0) {
            return BEDROCK;
        }
        return y < height(x, z) ? GRASS : AIR;
    }

    /** 4x4 周期微丘，值域 64 到 68。余数为 0 的边与中心取低地，两列斜边各取一条斜坡。 */
    private static int height(int x, int z) {
        int mx = Math.floorMod(x, 4);
        int mz = Math.floorMod(z, 4);
        if (mx == 0 || mz == 0) {
            return 64;
        }
        if (mx == 2 && mz == 2) {
            return 64;
        }
        if (mx == 1) {
            return 64 + mz;
        }
        if (mx == 3) {
            return 68 - mz;
        }
        return 66;
    }
}
