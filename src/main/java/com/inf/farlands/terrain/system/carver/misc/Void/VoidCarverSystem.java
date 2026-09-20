package com.inf.farlands.terrain.system.carver.misc.Void;

import com.inf.farlands.terrain.CarverSystem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** VOID 雕刻系统：不雕刻。状态推进、标脏与高度图 prime 由 CarverFiller 负责。 */
public final class VoidCarverSystem implements CarverSystem {

    @Override
    public void applyCarvers(ServerLevel level, ChunkAccess chunk) {
    }
}
