package com.inf.farlands.mixin.fix.xz;

import com.inf.farlands.terrain.decorationFiller.DecorationRegion;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.levelgen.feature.OreFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code OreFeature} 的段取数换成装饰区域的无锁版本。
 *
 * <p>这是装饰期唯一一处显式取调色板许可的地方：{@code new BulkSectionAccess(level)} 全树只出现在
 * {@code OreFeature.doPlace} 里，而它的 getSection 会对段调 acquire。调色板的并发检测器抢失败时
 * 通过抛异常把许可交出去，并行装饰会互相撞；而编码侧那个 catch 一旦吞掉异常，许可就永久占用。
 * 这里只需要读，而 {@code LevelChunkSection.getBlockState} 本来就不加锁，所以换成
 * {@link DecorationRegion.UnlockedSectionAccess}。
 *
 * <p>{@code @Redirect} 打的是构造点，即 NEW；处理体的形参就是构造器的形参表，返回类型是新建的那个
 * 类型。取数不是装饰区域时保持原样，避免把这条注入绑死在装饰路径上。
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureSectionAccessMixin {

    @Redirect(method = "doPlace", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/BulkSectionAccess"))
    private BulkSectionAccess farlands$unlockedSectionAccess(LevelAccessor level) {
        return level instanceof DecorationRegion region
                ? new DecorationRegion.UnlockedSectionAccess(region)
                : new BulkSectionAccess(level);
    }
}
