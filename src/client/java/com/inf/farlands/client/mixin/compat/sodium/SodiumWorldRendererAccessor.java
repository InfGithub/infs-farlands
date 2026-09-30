package com.inf.farlands.client.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * SodiumWorldRenderer.renderSectionManager 是 private，而读它的 SodiumWindowSyncImpl 不是 mixin，
 * 普通类里写 @Shadow 不生效。按本仓既有模式生成访问器而不是反射，见 render/LevelRendererAccessor。
 *
 * <p>
 * 加 @Pseudo 是因为 Sodium 可能缺席：目标解析不到时 Mixin 只记一条 DEBUG 并跳过本类，不报错也不加载。
 */
@Pseudo
@Mixin(SodiumWorldRenderer.class)
public interface SodiumWorldRendererAccessor {

    /** 当前 RenderSectionManager；Sodium 尚未建立关卡时为 null。 */
    @Accessor("renderSectionManager")
    RenderSectionManager farlands$renderSectionManager();
}
