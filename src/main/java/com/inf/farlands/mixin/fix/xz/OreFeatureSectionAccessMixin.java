package com.inf.farlands.mixin.fix.xz;

import com.inf.farlands.serialize.SectionSerializer;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code OreFeature} 的两处：段取数换成装饰区域的无锁版本，以及它那一次段写纳入包锁。
 *
 * <p>取数：{@code new BulkSectionAccess(level)} 全树只出现在 {@code OreFeature.doPlace} 里，而它的
 * getSection 会对段调 acquire。调色板的并发检测器抢失败时通过抛异常把许可交出去，而异常被编码侧的
 * catch 一旦吞掉，许可就永久占用。这里只需要读，而 {@code LevelChunkSection.getBlockState} 本来就不
 * 加锁，所以换成 {@link DecorationRegion.UnlockedSectionAccess}。
 *
 * <p>写入：矿石绕过 {@code DecorationRegion.setBlock} 直接调段的五参重载，那一路既不取包锁也不触发
 * 检测器，是全树唯一一处无锁改调色板的地方。它必须与其余六方共用同一把包锁，否则玩家写、下发序列化
 * 都会与它抢同一个容器。写点拿不到 chunk 引用，键由取段那一侧留在 {@link DecorationRegion} 的线程
 * 侧信道上。
 *
 * <p>两个 {@code @Redirect} 都用 {@code doPlace}：取数打的是构造点 NEW，处理体的形参就是构造器的形参
 * 表；写入打的是那一次 {@code setBlockState}，全方法只此一处，所以不带 ordinal。
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureSectionAccessMixin {

    @Redirect(method = "doPlace", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/BulkSectionAccess"))
    private BulkSectionAccess farlands$unlockedSectionAccess(LevelAccessor level) {
        return level instanceof DecorationRegion region
                ? new DecorationRegion.UnlockedSectionAccess(region)
                : new BulkSectionAccess(level);
    }

    @Redirect(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState farlands$oreWriteUnderPackLock(LevelChunkSection section, int localX, int localY,
            int localZ, BlockState state, boolean useLocks) {
        Long chunkKey = DecorationRegion.handedChunkKey();
        if (chunkKey == null) {
            // 非装饰路径：没有取段那一侧交下来的键，保持原样。
            return section.setBlockState(localX, localY, localZ, state, useLocks);
        }
        synchronized (SectionSerializer.packLockFor(chunkKey)) {
            return section.setBlockState(localX, localY, localZ, state, useLocks);
        }
    }
}
