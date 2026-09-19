package com.inf.farlands.mixin.terrain.surface;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SurfaceRules$Context 的成员访问器，供 SurfaceSystemMixin 的 buildSurface 复制体直调。
 *
 * <p>
 * 目标只能用 {@code targets} 字符串指定：{@code SurfaceRules$Context} 的 class access_flags
 * 虽是 public，但它的 <b>InnerClasses 属性是 protected</b>，而 javac 判断嵌套类可见性用的是
 * 后者——所以跨包无法引用 {@code SurfaceRules.Context} 这个类型，连 {@code .class} 字面量都写不了。
 * 上一版本（NeoForge 1.21.1）的注释记的就是这个现象，26.1.2 原版依然如此。因此本接口的方法
 * 签名只用 {@code int}，不出现该类型。
 *
 * <p>
 * 方法改用 {@code @Invoker} 而不是反射：updateY 是每格一次的热路径，生成访问器没有 invoke
 * 开销（本 port 的既有模式见 {@code noise/NoiseChunkInvoker}）。
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public interface SurfaceRules$ContextInvoker {

    @Invoker("updateXZ")
    void farlands$updateXZ(int blockX, int blockZ);

    @Invoker("updateY")
    void farlands$updateY(int stoneAboveDepth, int stoneBelowDepth, int waterHeight, int blockX, int y, int blockZ);

    @Invoker("getMinSurfaceLevel")
    int farlands$getMinSurfaceLevel();
}
