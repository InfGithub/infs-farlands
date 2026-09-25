package com.inf.farlands.client.mixin.integratedserver;

import com.inf.farlands.client.gui.CreatingWorldSystemsConfig;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.client.server.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把创建世界页签的选择交给服务端，让它成为这个世界落盘的 systems.dat。
 *
 * <p>注入点是 {@code initServer} 的 HEAD。它是服务端线程的第一格，{@code createLevels} 与主世界
 * 的 {@code ServerLevel} 构造都在它下游，而 {@link SystemsIO#resolve} 正是在那次构造里唯一一次
 * 读配置；在这里放进去，{@code resolve} 必然看得到。落盘仍由 {@code SavedDataStorage} 在
 * {@code initServer} 尾部的 {@code saveEverything} 完成，本 mixin 不碰文件。
 *
 * <p>目标类只有集成服务器，所以「仅单人」由类本身承担：专用服务器的 {@code MinecraftServer}
 * 子类不加载本 mixin。
 *
 * <p>暂存由客户端线程写、服务端线程读。{@code MinecraftServer.spin} 先构造服务器再
 * {@code thread.start()}，{@code start()} 的 happens-before 保证读侧看得到写侧的值，不需要额外同步。
 *
 * <p>文本非法时解析抛异常，注入点让异常直穿，世界创建中止并留下可见的失败，与既有的
 * {@code SystemsIO.read} 严格语义一致。
 *
 * <p>处理器收 {@link CallbackInfoReturnable} 而不是 {@code CallbackInfo}：{@code initServer}
 * 返回 boolean，判据是目标方法非 void，与是否 cancellable 无关。写错会在 APPLY 阶段抛
 * {@code InvalidInjectionException}，而构建全程通过。
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {

    @Inject(method = "initServer", at = @At("HEAD"))
    private void farlands$stageSystems(CallbackInfoReturnable<Boolean> cir) {
        SystemsIO.stageForLevelCreation(CreatingWorldSystemsConfig.buildSystemsData());
    }
}
