package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class LevelChunk$BoundTickingBlockEntityMixin<T extends BlockEntity> {

    @Shadow
    @Final
    private LevelChunk this$0;

    @Shadow
    public abstract BlockPos getPos();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void skipTickWhenSectionDiscarded(CallbackInfo ci) {
        LevelChunkSection s = ((WindowedChunk) this.this$0).windowedAllSections().get(getPos().getY() >> 4);
        if (s == null || s.hasOnlyAir()) {
            ci.cancel();
        }
    }
}