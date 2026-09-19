package com.inf.farlands.mixin.expand.y;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 关掉掉出世界判定。
 *
 * vanilla 的 checkBelowWorld 是 {@code getY() < getMinY() - 64} 就 onBelowWorld。getMinY 取维度值，
 * 恒为 -64，所以判据是 y < -128，与窗口无关。而本 port 的窗口在玩家竖直移动时会滑到 -64 以下，
 * 玩家可以合法地待在 -128 以下，那时这条判定会照常触发，把站在窗口内的玩家判成掉出世界。
 *
 * 与旧仓库同形：世界竖直方向不再有界，这条判定没有意义。
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Overwrite
    public void checkBelowWorld() {
    }
}
