package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.window.EntitySectionWindow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Overwrite
    public boolean shouldTickBlocksAt(BlockPos pos) {
        if ((Object) this instanceof ServerLevel && !EntitySectionWindow.inAnyWindow(pos.getY() >> 4)) {
            return false;
        }
        return ((Level) (Object) this).shouldTickBlocksAt(ChunkPos.pack(pos));
    }

    @SuppressWarnings("resource")
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void rejectWindowOutside(BlockPos pos, BlockState state, int flags, int recursionLeft,
            CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            return;
        }
        int sectionY = pos.getY() >> 4;
        if (EntitySectionWindow.isOutsideAllWindows(sectionY, FarlandsConfig.fsaCleanupMargin)) {
            cir.setReturnValue(false);
        }
    }
}