package com.inf.farlands.client.compat.sodium;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.client.mixin.compat.sodium.SodiumWorldRendererAccessor;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sodium 侧同步的实现，是整层唯一允许出现 Sodium 类型的普通类。
 *
 * <p>
 * onWindowChanged 的签名保持 Sodium 无关。调用它的 SodiumWindowSync 在加载与校验时只需要本类的签名，
 * 不需要解析 Sodium 的类，所以没装 Sodium 的客户端不会因为本类而失败；Sodium 类型只出现在方法体内，
 * 只有门控为真、这一方法真的被执行时才需要它们。
 *
 * <p>
 * 窗口变化时先补 onChunkAdded，它按该 chunk 的当前窗口逐个 section 建立，Sodium 侧对已存在的 section
 * 是幂等的；再按旧窗口减去新窗口，逐个 onSectionRemoved。Sodium 的 section 树按 XZ 管理，Y 方向残留
 * 没有自动清理，极端 Y 往返会持续累积。
 */
final class SodiumWindowSyncImpl {

    private SodiumWindowSyncImpl() {
    }

    static void onWindowChanged(LevelChunk chunk, int oldMinY, int newMinY) {
        SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
        RenderSectionManager manager = worldRenderer == null ? null
                : ((SodiumWorldRendererAccessor) worldRenderer).farlands$renderSectionManager();
        if (manager == null) {
            return;
        }
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        manager.onChunkAdded(chunkX, chunkZ);
        if (oldMinY == Integer.MIN_VALUE) {
            return;
        }
        int oldMaxY = windowMaxY(oldMinY);
        for (int sectionY = oldMinY; sectionY < newMinY && sectionY <= oldMaxY; sectionY++) {
            manager.onSectionRemoved(chunkX, sectionY, chunkZ);
        }
        // 下界必须夹在旧窗口内：向下大跳时 newMaxY + 1 远低于 oldMinY，不夹会遍历上亿次。
        int newMaxY = windowMaxY(newMinY);
        for (int sectionY = Math.max(newMaxY + 1, oldMinY); sectionY <= oldMaxY; sectionY++) {
            manager.onSectionRemoved(chunkX, sectionY, chunkZ);
        }
    }

    /** 窗口是对称的 center 加减 verticalSimulationDistance，长度 2N+1，故下界加 2N 即上界。 */
    private static int windowMaxY(int windowMinY) {
        return windowMinY + FarlandsConfig.verticalSimulationDistance * 2;
    }
}
