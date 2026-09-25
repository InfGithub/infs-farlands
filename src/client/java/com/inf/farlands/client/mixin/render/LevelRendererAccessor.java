package com.inf.farlands.client.mixin.render;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * LevelRenderer 的 viewArea 访问器。
 *
 * <p>该字段是 private，而读它的 {@code LightUpdatePacketRegister} 不是 mixin，普通类里写 {@code @Shadow}
 * 不生效。本仓既有模式是生成访问器而不是反射，见 {@code mixin/noise/NoiseChunkInvoker} 与
 * {@code HeightmapInvoker}。
 *
 * <p>存在理由：{@code LevelRenderer.setLevel(null)} 在卸载关卡时把 viewArea 置空并释放缓冲，
 * 而 {@code setSectionDirty} 不判空。客户端的自建光照增量包在关服收尾到达时就会踩到那个空值。
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    /** 当前视图区，未建关卡时为 null。 */
    @Accessor("viewArea")
    ViewArea farlands$viewArea();
}
