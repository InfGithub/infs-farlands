package com.inf.farlands.client.mixin.light;

import com.inf.farlands.light.FarLandsLightEngine;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 将 ClientChunkCache 构造中的 {@code new LevelLightEngine}
 * 替换为 {@link FarLandsLightEngine}。ClientChunkCache 仅客户端使用，
 * 因此本 mixin 放在 client 源集 / client mixin 配置。
 *
 * <p>onLightUpdate 不替换：vanilla ClientChunkCache.onLightUpdate 已是
 * setSectionDirty，我们的引擎经 chunkSource.onLightUpdate 虚分派即到达；
 * 保留 vanilla 方法使其它 mod 的 ClientChunkCache 注入能命中。
 *
 * <p>26.1.2：客户端构造实参仍是 (LightChunkGetter, boolean, boolean)，不变。
 */
@Mixin(ClientChunkCache.class)
public class ClientChunkCacheLightEngineMixin {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/world/level/lighting/LevelLightEngine"))
    private LevelLightEngine redirectNewLightEngine(LightChunkGetter chunkSource, boolean blockLight, boolean skyLight) {
        return new FarLandsLightEngine(chunkSource, skyLight);
    }
}
