package com.inf.farlands.terrain;

import net.minecraft.world.level.chunk.CarvingMask;

/**
 * carving mask 存储接口，由 ChunkAccessMixin 注入到 ChunkAccess。
 *
 * vanilla 的 carvingMask 挂在 ProtoChunk 上，本 port 的 chunk 短路后是 LevelChunk，
 * 没有这个字段，因此按 WindowedChunk 的方式用接口注入。26.1.2 只有单个 mask，
 * 没有 GenerationStep.Carving 分组。mask 用于目标 chunk 的雕刻去重，内存有界。
 */
public interface CarvingMaskStorage {

    /** 取 mask，未创建时返回 null。 */
    CarvingMask getCarvingMask();

    /** 取或创建，范围是该 chunk 的高度。 */
    CarvingMask getOrCreateCarvingMask();

    void setCarvingMask(CarvingMask mask);
}
