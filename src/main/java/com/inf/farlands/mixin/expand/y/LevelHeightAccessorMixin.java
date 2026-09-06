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
        if ((Object) this instanceof ChunkAccess) {
            LevelHeightAccessor self = (LevelHeightAccessor) (Object) this;
            return y < self.getMinY() || y > self.getMaxY();
        }
        return !WorldBounds.inBuildHeight(y);
    }
}