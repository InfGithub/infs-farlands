package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;

import net.minecraft.world.level.lighting.LevelLightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 光照 section 跨度按窗口高度取，不按维度高度取。
 *
 * vanilla 用维度高度推导这三个值：getLightSectionCount = getSectionsCount + 2，
 * getMinLightSection = getMinSectionY - 1。维度竖直度量放宽到全 Y 之后，前者的值接近 21 亿，
 * 而它在 ChunkHolder、ThreadedLevelLightEngine.updateChunkStatus、
 * ClientboundLightUpdatePacketData 里都是循环或 BitSet 的界，会直接炸。
 *
 * 光照实际只需要覆盖玩家窗口上下各一格。窗口半高由 verticalSimulationDistance 决定，
 * 所以这里取 2 * half + 3 = 窗口段数加两格 padding。绝对 sectionY 的记账在
 * ChunkHolderMixin 与 LightUpdatePacket 里，不受这里的跨度影响。
 */
@Mixin(LevelLightEngine.class)
public class LevelLightEngineMixin {

    @Overwrite
    public int getLightSectionCount() {
        return FarlandsConfig.verticalSimulationDistance * 2 + 3;
    }

    @Overwrite
    public int getMinLightSection() {
        return -FarlandsConfig.verticalSimulationDistance - 1;
    }

    @Overwrite
    public int getMaxLightSection() {
        return this.getMinLightSection() + this.getLightSectionCount();
    }
}
