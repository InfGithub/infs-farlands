package com.inf.farlands.terrain.system.misc.surfaceVoid;

import com.inf.farlands.terrain.SurfaceSystem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** VOID 地表系统：不做任何地表处理。状态推进与标脏由 SurfaceFiller 负责。 */
public final class VoidSurfaceSystem implements SurfaceSystem {

    @Override
    public void applySurface(ServerLevel level, ChunkAccess chunk) {
    }
}
