package com.inf.farlands.mixin.terrain.surface;

import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SurfaceRules$SurfaceRule 的 tryApply 访问器。
 *
 * <p>
 * 与 {@link SurfaceRules$ContextInvoker} 同样的原因：{@code SurfaceRules$SurfaceRule} 的
 * InnerClasses 属性是 protected，类型不可跨包引用，只能 targets 字符串指定。返回类型
 * {@code BlockState} 是普通类，可以正常引用。
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule")
public interface SurfaceRules$SurfaceRuleInvoker {

    @Invoker("tryApply")
    BlockState farlands$tryApply(int blockX, int blockY, int blockZ);
}
