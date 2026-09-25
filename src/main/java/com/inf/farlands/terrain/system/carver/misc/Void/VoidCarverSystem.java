package com.inf.farlands.terrain.system.carver.misc.Void;

import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** VOID 雕刻系统：不雕刻。状态推进、标脏与高度图 prime 由 CarverFiller 负责。 */
public final class VoidCarverSystem implements CarverSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidCarverSystem(SystemArgs args) {
    }

    @Override
    public void applyCarvers(ServerLevel level, ChunkAccess chunk) {
    }
}
