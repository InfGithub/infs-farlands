package com.inf.farlands.terrain.system.surface.misc.Void;

import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** VOID 地表系统：不做任何地表处理。状态推进与标脏由 SurfaceFiller 负责。 */
public final class VoidSurfaceSystem implements SurfaceSystem {

    /** 声明：无参数，界面给 0 个框。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.EMPTY;

    /** 统一构造签名，本系统不读任何参数。 */
    public VoidSurfaceSystem(SystemArgs args) {
    }

    @Override
    public void applySurface(ServerLevel level, ChunkAccess chunk) {
    }
}
