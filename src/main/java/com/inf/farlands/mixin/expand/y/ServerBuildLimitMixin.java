package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 放置与破坏的建造高度判定放宽到世界生成界。
 *
 * vanilla 在 handleUseItemOn 里取 level.getMaxY() 与 getMinY()，pos.getY() 高于 maxY 就发
 * sendBuildLimitMessage 的上界提示，低于 minY 发下界提示；handlePlayerAction 把
 * level.getMaxY() 传给 handleBlockBreakAction，那里只查上界。两处各只有一个调用点，后续判断
 * 读的是局部变量，所以覆写这几个返回值即可。维度竖直度量未放宽时它们是 320 与 -64，窗口滑到
 * 范围外就全被拦。
 *
 * 这两个值不能用 chunk 窗口算。服务端从未移动过 chunk 窗口，构造默认停在 section -4，读它得到
 * 的上界是 -64，反而会把 y=64 的正常放置与挖掘全部拦掉，提示上界为 -64。旧仓库同样把服务端
 * 窗口当死状态，建筑高度界直接用 Config 的世界生成界。
 *
 * 不能直接覆写 getMinY, getSectionsCount、高度图容量、光照引擎的维度假设都挂在它上面。
 * 真正拦下窗口外写入的是 LevelMixin.rejectWindowOutside，即玩家窗口并集判定，与旧仓库
 * ServerLevelSetBlockMixin 同形，因此这里放宽不会放进越界方块。
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public class ServerBuildLimitMixin {

    @Redirect(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
    private int farlands$placeMaxY(ServerLevel level) {
        return FarlandsConfig.worldGenMaxY;
    }

    @Redirect(method = "handleUseItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private int farlands$placeMinY(ServerLevel level) {
        return FarlandsConfig.worldGenMinY;
    }

    @Redirect(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxY()I"))
    private int farlands$breakMaxY(ServerLevel level) {
        return FarlandsConfig.worldGenMaxY;
    }
}
