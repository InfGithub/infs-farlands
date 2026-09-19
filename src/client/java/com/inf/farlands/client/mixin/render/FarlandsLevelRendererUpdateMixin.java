package com.inf.farlands.client.mixin.render;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每帧把窗口拉正，而不是只在相机跨 section 时。
 *
 * 背景：ViewArea.repositionCamera 在 26.1.2 只在相机跨 section 时被调用一次：
 * LevelRenderer.cullTerrain 里比较 lastCameraSectionX/Y/Z 后才调。于是它只是一次性的，
 * 在某次跨段时扫过的 chunk 被拉正，之后加载进来的 chunk 永远没被扫到。
 *
 * 而 chunk 的 windowSections 构造默认只有 1 段，windowMinY=-4，不改就装不下内容：
 * SectionCopy 用的索引是 sectionY - windowMinY，数组长度只有 1，于是索引越界拿到 null，
 * 该 chunk 编译成空气。实测里 winMinY=-4 / winLen=1 的那批 chunk 全部 atIdx=OOB 或 air，
 * 而 winMinY=-12 / winLen=35 的那批 atIdx=NONEMPTY，取数正常。
 *
 * LevelRenderer.update(Camera) 每帧调用，cullTerrain 与 compileSections 都在它内部，是最合适
 * 的每帧钩子。调用的仍是被移植过来的 repositionCamera，扫描逻辑与早退都在那里，本类不重复实现。
 */
@Mixin(LevelRenderer.class)
public class FarlandsLevelRendererUpdateMixin {

    @Shadow
    private ViewArea viewArea;

    @Inject(method = "update", at = @At("HEAD"))
    private void farlands$repositionCameraEveryFrame(Camera camera, CallbackInfo ci) {
        if (camera != null && this.viewArea != null) {
            this.viewArea.repositionCamera(SectionPos.of(camera.blockPosition()));
        }
    }
}
