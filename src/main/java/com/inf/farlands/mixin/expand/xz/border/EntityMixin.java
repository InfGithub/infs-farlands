package com.inf.farlands.mixin.expand.xz.border;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.Entity;

@Mixin(Entity.class)
public class EntityMixin {

    @Redirect(method = "absSnapTo(DDD)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"))
    private static double absSnapTo(double value, double min, double max) {
        return value;
    }

    /**
     * 反序列化时的三处坐标钳制改成原样放行，即取消 ±3.0000512E7 与 ±2.0E7 这组世界边界。
     *
     * <p>{@code Entity.load} 是把外来坐标灌进实体位置的唯一入口，三条没有事后还原的路径都终点在这里：
     * 刷怪蛋的 {@code TypedEntityData.loadInto}、{@code /data merge entity} 的
     * {@code EntityDataAccessor.setData}、以及存档读盘。这三条都不再还原坐标，所以整组边界把它们的
     * 出生坐标拉到 ±30000512。{@code /summon} 不受影响，它在 postLoad 里重新 snapTo。
     *
     * <p>取消是安全的，三处职责都已核实：
     * <ul>
     * <li>NaN 与 Infinity 不由本钳制负责。{@code load} 在 {@code setPosRaw} 之后紧跟一处有限性检查，
     *     非有限值抛 {@code IllegalStateException("Entity has invalid position")}，取消后这条门原样保留。</li>
     * <li>超大但有限的值由 {@code Entity.setPosRaw} 的 {@code Mth.floor} 收口，它按 Java 窄化语义饱和到
     *     int 边界，所以 blockPosition 与 chunkPosition 与钳到 {@code FarlandsConfig.borderAbsoluteMax}
     *     逐位相同，只有 position 字段保留原值。</li>
     * <li>{@code setPosRaw} 之前没有解引用位置的语句，取消不会让任何语句落在未钳值上。</li>
     * </ul>
     *
     * <p>同族里其余每一道门都已改成 {@code WorldBounds} 或 Config 的边界，本方法是那一族里最后一处
     * 仍锚在 ±30000000 的实体位置判定。{@code Player.tick} 那道钳制不在此列，它另由本包的
     * {@code PlayerMixin} 改到 {@code borderAbsoluteMax - 1}。
     *
     * <p>注入点定位：{@code load} 里 {@code Mth.clamp(DDD)D} 恰好三处，即 x、y、z 各一次，字节码里与
     * {@code setPosRaw} 相邻；方法内其余的 clamp 在别的方法里，不受影响。两条 redirect 各自按指令节点
     * 记账，不会互相吞并，但对 {@code @Redirect} 的重定向冲突只告警并跳过，所以改这里之后必须实机确认。
     */
    @Redirect(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"))
    private static double load(double value, double min, double max) {
        return value;
    }
}
