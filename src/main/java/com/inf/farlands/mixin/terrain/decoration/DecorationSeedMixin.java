package com.inf.farlands.mixin.terrain.decoration;

import com.inf.farlands.terrain.decorationFiller.DecorationRandom;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomSupport;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 装饰放置的随机源种子换成装饰系统交进来的那一个。
 *
 * <p>{@code applyBiomeDecoration} 方法体内建随机源那一段逐条是
 * {@code new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()))}，
 * 本类拦其中取种子的那一处普通静态调用 {@code RandomSupport.generateUniqueSeed()}，把它的返回值换成
 * 系统声明的 seed。构造与赋值那两条指令逐字不动，所以种子之外的语义，即 {@code setDecorationSeed}
 * 的位置校验、{@code setFeatureSeed} 的逐步取种、结构相的分组缓存，全部保持原样。
 *
 * <p>只拦取种子这一条、不去拦 {@code new XoroshiroRandomSource}：构造器调用上的 {@code @Redirect}
 * 会被框架拒，报错是 {@code Illegal @Redirect of constructor specified}；而拦 {@code NEW} 的工厂重定向
 * 在 APPLY 阶段又找不到配对的构造器调用，报错是 {@code @Redirect ctor invocation was not found}。取种子这
 * 一条是普通 {@code INVOKE}，与仓库里既有的同形写法一致，见 {@code NoiseRouterDataMixin}。
 *
 * <p>目标方法在本 port 已被 {@code mixin/expand/y/ChunkGeneratorMixin} 覆写，本 port 的方法体里这一
 * 次静态调用出现一次，{@code javap} 打在运行时 jar 上核对过；默认 {@code require = 1}，命中数不符即
 * 失败。
 *
 * <p>优先级取 1001，比 {@code expand/y.ChunkGeneratorMixin} 的默认 1000 严格更大。理由是那一个是
 * {@code @Overwrite}：它把这个方法合并标记，而同级或更高级别的后来者不能往被合并的方法里注入。数值
 * 小的先应用，所以必须先让覆写落成，再在本类的注入点里改它产出的那条指令。调低本值会直接 CTD，串为
 * {@code cannot inject into ... merged by ... with priority 1000}。
 *
 * <p>未设置侧信道时返回 vanilla 的唯一种子，与不装本模组时的行为逐字相同。
 */
@Mixin(value = ChunkGenerator.class, priority = 1001)
public class DecorationSeedMixin {

    @Redirect(method = "applyBiomeDecoration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomSupport;generateUniqueSeed()J"))
    private static long farlands$decorationSeed() {
        Long seed = DecorationRandom.get();
        return seed != null ? seed : RandomSupport.generateUniqueSeed();
    }
}
