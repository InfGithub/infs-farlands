package com.inf.farlands.client.mixin.render;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每帧把 chunk 窗口拉正到相机段。
 *
 * chunk 的 windowSections 构造默认只有 1 段，windowMinY=-4，不改就装不下内容：SectionCopy 用的
 * 索引是 sectionY - windowMinY，数组长度只有 1，于是索引越界拿到 null，该 chunk 编译成空气。
 * 窗口必须每帧拉，因为 chunk 会在相机不动时陆续加载进来，而 vanilla 只在相机跨 section 时才会
 * 扫一遍，之后加载的 chunk 永远没被扫到。
 *
 * 不调 vanilla 的 repositionCamera：它结尾会 getSectionOcclusionGraph().invalidate()，每帧调等于
 * 每帧整图重建，还附赠每帧一遍全部渲染槽位的比较与重指。渲染网格的竖直环绕改由 vanilla 自己的
 * 跨段守卫驱动，那个守卫比较相机 section 的 X/Y/Z 三者，且 cullTerrain 在 compileSections 之前，
 * 时序够用。
 *
 * 只枚举已加载 chunk：未加载的没有数据可显示，等它加载时构造路径会自己建窗口。buildWindow 在
 * 窗口未变时早退，因此每帧调用在相机不跨 section 时零开销。
 */
@Mixin(LevelRenderer.class)
public class FarlandsLevelRendererUpdateMixin {

    @Shadow
    private ViewArea viewArea;

    @Shadow
    private ClientLevel level;

    @Inject(method = "update", at = @At("HEAD"))
    private void farlands$followCameraWindow(Camera camera, CallbackInfo ci) {
        if (camera == null || this.viewArea == null || this.level == null) {
            return;
        }
        SectionPos camSection = SectionPos.of(camera.blockPosition());
        int camSecY = camSection.y();
        int radius = this.viewArea.getViewDistance();
        ChunkPos cpos = camSection.chunk();
        for (int cx = cpos.x() - radius; cx <= cpos.x() + radius; cx++) {
            for (int cz = cpos.z() - radius; cz <= cpos.z() + radius; cz++) {
                LevelChunk chunk = (LevelChunk) this.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
                    ((WindowedChunk) chunk).moveWindowTo(camSecY);
                }
            }
        }
    }
}
