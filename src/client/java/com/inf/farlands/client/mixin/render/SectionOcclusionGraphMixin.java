package com.inf.farlands.client.mixin.render;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 遮挡图洪泛的竖直上限对齐网格半高。
 *
 * vanilla 的 getRelativeFrom 用水平视距当竖直上限，而渲染网格的竖直半径是
 * verticalSimulationDistance，两者不等时网格最外一圈永远遍历不到，那一圈不会进 octree，
 * 也就不进可见集，不渲染。
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {

    @Redirect(method = "getRelativeFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;getViewDistance()I"))
    private static int farlands$gridHalfSpan(ViewArea viewArea) {
        return FarlandsConfig.verticalSimulationDistance;
    }
}
