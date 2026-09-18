package com.inf.farlands.mixin.light;

import com.inf.farlands.light.FarLandsLightQueue;

import net.minecraft.core.registries.BuiltInRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 light ticket 类型（{@code infs-farlands:light_chunk_work}）的注册提前到
 * BuiltInRegistries 冻结之前。
 *
 * <p>BuiltInRegistries.bootStrap() = createContents() + freeze() + validate()，
 * freeze() 一执行，任何 Registry.register 都抛 "Registry is already frozen"。
 * 而两个"自然"时机都太晚：
 * <ul>
 * <li>mod 初始化：Fabric 的 EntrypointPatch 把 onInitialize 挂在主类 main 的
 * new Minecraft(...) 附近，晚于 Bootstrap.bootStrap()</li>
 * <li>懒加载 {@code <clinit>}：第一次用到 FarLandsLightQueue 是开世界构造
 * FarLandsLightEngine，那时注册表早已冻结</li>
 * </ul>
 *
 * <p>故在 bootStrap() 头部强制初始化 FarLandsLightQueue：它 {@code <clinit>} 里的
 * Registry.register(BuiltInRegistries.TICKET_TYPE, ...) 于是在冻结点之前执行，
 * 且类初始化恰好一次，注册无需额外幂等守卫。
 */
@Mixin(BuiltInRegistries.class)
public class BuiltInRegistriesMixin {

    @Inject(method = "bootStrap", at = @At("HEAD"))
    private static void registerTicketTypes(CallbackInfo ci) {
        FarLandsLightQueue.ensureTicketTypeRegistered();
    }
}
