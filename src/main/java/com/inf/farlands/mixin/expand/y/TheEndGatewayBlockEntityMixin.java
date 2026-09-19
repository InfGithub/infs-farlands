package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 末地门的取最高方块扫描封顶。
 *
 * vanilla 从 getMaxY() 往下扫到 getMinY()。本 port 的维度值仍是 vanilla 的 -64..320，所以扫描
 * 量本来就与 vanilla 相同；封顶只是防御：一旦维度高度被撑开，这里会退化成逐格扫遍全高。
 * 封顶上限用 maxCapIter，与其它极端 Y 循环一致。
 *
 * 与 vanilla 的差别只有循环下界：取 [minY, minY + maxCapIter] 与已扫描位置的较大者。
 */
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class TheEndGatewayBlockEntityMixin {

    @Overwrite
    private static BlockPos findTallestBlock(BlockGetter level, BlockPos pos, int radius, boolean allowBedrock) {
        BlockPos tallest = null;
        int absoluteMinY = level.getMinY();
        int scanTop = Math.min(level.getMaxY(), absoluteMinY + FarlandsConfig.maxCapIter);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0 && !allowBedrock) {
                    continue;
                }
                int bottom = tallest == null ? absoluteMinY : Math.max(tallest.getY(), absoluteMinY);
                for (int y = scanTop; y > bottom; y--) {
                    BlockPos at = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                    BlockState state = level.getBlockState(at);
                    if (state.isCollisionShapeFullBlock(level, at) && (allowBedrock || !state.is(Blocks.BEDROCK))) {
                        tallest = at;
                        break;
                    }
                }
            }
        }

        return tallest == null ? pos : tallest;
    }
}
