package com.inf.farlands.mixin.noise;

import net.minecraft.world.level.levelgen.Heightmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Heightmap 的 private setHeight 访问器，供 CarverFiller 的自研 prime 写入。
 *
 * vanilla primeHeightmaps 的扫描范围是 [getHighestSectionPosition + 16, getMinY]，
 * 在本 port 的窗口语义下 getHighestSectionPosition 极端 Y 可达 21 亿，是扫描炸弹，
 * 不能直接调，因此自研 prime 用列掩码加非空 section 降序定位，再用本访问器写入。
 */
@Mixin(Heightmap.class)
public interface HeightmapInvoker {

    @Invoker("setHeight")
    void farlands$setHeight(int x, int z, int y);
}
