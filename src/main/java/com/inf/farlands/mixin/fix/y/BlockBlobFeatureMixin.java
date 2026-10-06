package com.inf.farlands.mixin.fix.y;

import com.inf.farlands.terrain.decorationFiller.DecorationRegion;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 苔石堆地物的柱状下行扫描：地板从世界下界抬到装饰读域里最低的已物化段。
 *
 * <p>vanilla 的 {@code place} 从原点逐格下行，直到下方方块满足 {@code canPlaceOn} 或到达
 * {@code getMinY() + 3}，一次一格，每格一次方块读取加一次 tag 判定。vanilla 里这个跨度上限是 381 格，
 * 因为原点最高 320 而维度下界是 -64。本 port 的地表在十亿格量级，运行期的 {@code getMinY()} 却仍是
 * -64，于是没有可放置方块的那些列会把整个跨度走完，实测一次约 47 秒，而 {@code forest_rock} 每个
 * chunk 尝试两次。
 *
 * <p>抬地板不改变结果：地板以下的读一律是空气。本 port 的 {@code LevelChunk.getBlockState} 只在段容器
 * 里有该段且该段非全空气时才读它，缺段直接返回空气，而地板正是读域里最低的已物化段再往下 8 格，所以
 * 地板以下永远命中不了那个 tag。地板只压缩步数，不改变循环返回什么。
 *
 * <p>地板来自段容器而不是 {@code getWindowMinY()}：服务端 chunk 的窗口是构造默认的死状态，不跟生成走，
 * 读它会得到 -4 那个默认值，抬不动地板。
 *
 * <p>两处 {@code getMinY()} 调用点是同一个表达式的两处，循环条件与循环后的判定。{@code @Redirect}
 * 不带 {@code ordinal} 会把它们一起覆盖，两处口径因此仍然一致。{@code target} 的所属类是字节码里的
 * {@code WorldGenLevel}，不是声明该方法的 {@code LevelHeightAccessor}，用 {@code javap -c} 核过。
 */
@Mixin(BlockBlobFeature.class)
public abstract class BlockBlobFeatureMixin {

    @Redirect(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinY()I"))
    private static int farlands$raiseDescentFloor(WorldGenLevel level) {
        int original = level.getMinY();
        // 非装饰区域的调用保持原口径：那里没有段容器可问，地板就是维度下界。
        if (level instanceof DecorationRegion region) {
            return Math.max(original, region.descentFloorY());
        }
        return original;
    }
}
