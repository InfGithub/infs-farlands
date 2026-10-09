package com.inf.farlands.terrain.system.terrain.block.misc.TheFourthDimension;

import com.inf.farlands.terrain.BlockSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 第四维：y 为 63 以下是实心，之上只留 section 网格的棱。
 *
 * <p>
 * 判定按书写顺序取第一条命中，所以多层条件同时成立时以靠前的那条为准。y 不大于 -63 是基岩，
 * 不大于 60 是石头，不大于 62 是泥土，等于 63 是草方块，再往上只有两种石头。
 *
 * <p>
 * y 大于 63 的石头有两个来源。其一是 chunk 局部 x 与 z 都为零的那一列，条件里没有 y 的上界，
 * 是每个 chunk 一根从 64 直通可玩 section 上界的石柱。其二是 section 顶层，即 y 与 15 位与为 15
 * 的那一层，沿 x 或 z 的局部零线，每个 section 顶层 31 格。两者合起来勾出 section 网格的棱。
 *
 * <p>
 * y 等于 63 是第 3 个 section 的顶层，被草方块那两条先占用，所以棱从 y 等于 79 才开始，草地面
 * 之上留 16 层净空。
 *
 * <p>
 * 局部坐标一律取 x &amp; 15 与 y &amp; 15，不取模 16：负坐标下模给负余数，判定会整体错位。对 16
 * 取模，位与与 Math.floorMod 同值。
 *
 * <p>
 * 无状态：fillBlock 是纯函数，实例随 level 走、可跨 genPool 线程共享。
 */
public final class TheFourthDimensionBlockSystem implements BlockSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

    /** 统一构造签名，本系统不读任何参数。 */
    public TheFourthDimensionBlockSystem(SystemArgs args) {
    }

    @Override
    public BlockState fillBlock(int x, int y, int z) {
        if (y <= -63) {
            return BEDROCK;
        }
        if (y <= 60) {
            return STONE;
        }
        if (y <= 62) {
            return DIRT;
        }
        int lx = x & 15;
        int lz = z & 15;
        if (y == 63) {
            return lx == 0 && lz == 0 ? DIRT : GRASS;
        }
        // 走到这里 y 必然大于 63。角柱在每一层都成立，两条局部零线只在 section 顶层成立。
        if (lx == 0 && lz == 0) {
            return STONE;
        }
        if ((y & 15) == 15 && (lx == 0 || lz == 0)) {
            return STONE;
        }
        return AIR;
    }
}
