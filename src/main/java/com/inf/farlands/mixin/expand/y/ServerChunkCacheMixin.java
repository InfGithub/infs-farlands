package com.inf.farlands.mixin.expand.y;

import java.io.IOException;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.storage.SavedDataStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    private ThreadedLevelLightEngine lightEngine;

    @Shadow
    private SavedDataStorage savedDataStorage;

    @Shadow
    public ChunkMap chunkMap;

    @Shadow
    public void save(boolean flush) {
    }

    @Overwrite
    public void close() throws IOException {
        try {
            this.save(true);
        } finally {
            this.savedDataStorage.close();
            this.lightEngine.close();
            this.chunkMap.close();
        }
    }
}