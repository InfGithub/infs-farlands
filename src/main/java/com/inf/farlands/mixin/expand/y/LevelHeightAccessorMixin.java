package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    @Overwrite
    default int getMinSectionY() {
        if ((Object) this instanceof ImposterProtoChunk ipc) {
            return ((WindowedChunk) ipc.getWrapped()).getWindowMinY();
        }
        if ((Object) this instanceof LevelChunk lc) {
            return ((WindowedChunk) lc).getWindowMinY();
        }
        LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
        return SectionPos.blockToSectionCoord(self.getMinY());
    }

    @Overwrite
    default int getMaxSectionY() {
        if ((Object) this instanceof ImposterProtoChunk ipc) {
            return ((WindowedChunk) ipc.getWrapped()).getWindowMaxY();
        }
        if ((Object) this instanceof LevelChunk lc) {
            return ((WindowedChunk) lc).getWindowMaxY();
        }
        LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
        return SectionPos.blockToSectionCoord(self.getMaxY());
    }

    @Overwrite
    default int getSectionsCount() {
        if ((Object) this instanceof ChunkAccess ca) {
            return ca.getSections().length;
        }
        LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
        return self.getMaxSectionY() - self.getMinSectionY() + 1;
    }

    @Overwrite
    default int getSectionYFromSectionIndex(int sectionIndex) {
        if ((Object) this instanceof ImposterProtoChunk ipc) {
            return ((WindowedChunk) ipc.getWrapped()).getWindowMinY() + sectionIndex;
        }
        if ((Object) this instanceof LevelChunk lc) {
            return ((WindowedChunk) lc).getWindowMinY() + sectionIndex;
        }
        LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
        return sectionIndex + self.getMinSectionY();
    }

    @Overwrite
    default int getSectionIndexFromSectionY(int sectionY) {
        if ((Object) this instanceof ImposterProtoChunk ipc) {
            return sectionY - ((WindowedChunk) ipc.getWrapped()).getWindowMinY();
        }
        if ((Object) this instanceof LevelChunk lc) {
            return sectionY - ((WindowedChunk) lc).getWindowMinY();
        }
        LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
        return sectionY - self.getMinSectionY();
    }

    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        return !WorldBounds.inBuildHeight(y);
    }

    /**
     * 26.1.2 的 Level.getBlockState 与 setBlock 走 isInValidBounds，那里读的是本方法，不再是
     * isOutsideBuildHeight。两者必须同源，否则维度界外的读写会被静默挡掉：读返回 VOID_AIR，
     * 写返回 false 且没有任何提示。
     *
     * 不能对 ChunkAccess 单独用维度界。地形按窗口段生成，-64 以下由 fill 直写 section 产出，
     * 而 SurfaceSystem 与 ProtoChunk 的高度检查受体正是 chunk 自己，走维度界会让这一段的地表
     * 写入被静默跳过。竖直方向的真实门是 LevelMixin.rejectWindowOutside 的玩家窗口判定。
     */
    @Overwrite
    default boolean isInsideBuildHeight(int y) {
        return WorldBounds.inBuildHeight(y);
    }
}