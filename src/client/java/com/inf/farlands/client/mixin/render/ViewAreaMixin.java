package com.inf.farlands.client.mixin.render;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端窗口跟随相机 Y。
 *
 * chunk 的 windowSections 构造默认只有一段，即 windowMinY 那一段，而 SectionCopy
 * 是按 getSectionIndexFromSectionY 取 getSections()[index] 的。窗口不拉正时只有
 * 默认那一段能取到数据，其余全渲染为空。旧仓库由 ViewAreaMixin.repositionCamera
 * 每帧给附近每个 chunk 调 moveWindowTo 解决，本类是那段逻辑的移植。
 *
 * 只枚举已加载 chunk：未加载的没有数据可显示，等它加载时构造路径会自己建窗口。
 * buildWindow 在窗口未变时早退，因此每帧调用在相机不跨 section 时零开销。
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

    @Shadow
    protected Level level;

    @Inject(method = "repositionCamera", at = @At("RETURN"))
    private void farlands$followCameraWindow(SectionPos cameraSectionPos, CallbackInfo ci) {
        ViewArea self = (ViewArea) (Object) this;
        int camSecY = cameraSectionPos.y();
        int radius = self.getViewDistance();
        ChunkPos cpos = cameraSectionPos.chunk();
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
