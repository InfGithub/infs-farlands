package com.inf.farlands.mixin.terrain.decoration;

import com.inf.farlands.terrain.decorationFiller.DecorationContext;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 装饰期不让方块实体持有 live level。
 *
 * <p>为什么必须处置：装饰期建出的方块实体，其 {@code level} 字段会被赋成该 level 实例，也就是
 * ServerLevel；地物随后经由方块实体发起的调用因此绕过装饰区域直接落到活世界上。实测那条路是
 * 刷怪笼：{@code MonsterRoomFeature} 设 entityId，走 SpawnerBlockEntity 覆写的 setNextSpawnData，
 * 里面读 {@code level.getBlockState} 并发 {@code level.sendBlockUpdated}。
 *
 * <p>处置取写 null，不是写区域：字段与 setLevel 的形参都是 {@code Level}，而装饰区域是
 * WorldGenLevel，经 LevelAccessor 并不继承 Level，存不进这个字段。写 null 之后，那两处的
 * {@code if (level != null)} 直接跳过，活世界入口在源头被掐掉。装饰期建的实体只在装饰期存活很短，
 * 之后由收尾在主线程重新赋 level。
 *
 * <p>落点选字段写入而不是 setLevel 的调用点：字段写入全树只有一处，就在 setLevel 的方法体里，
 * 一次覆盖 {@code LevelChunk.setBlockEntity} 与 {@code promotePendingBlockEntity} 两条路径。
 *
 * <p>判定用线程局部：装饰跑在 farlands-gen 上，而 worker 是池里复用的线程，全局标志会污染同池的
 * 地形任务；未处于装饰时原样返回传入值，读盘与正常游戏路径逐字不变。
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityLevelMixin {

    @Shadow
    protected Level level;

    @Redirect(method = "setLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;level:Lnet/minecraft/world/level/Level;", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void farlands$noLiveLevelWhileDecorating(BlockEntity blockEntity, Level value) {
        this.level = DecorationContext.isDecorating() ? null : value;
    }
}
