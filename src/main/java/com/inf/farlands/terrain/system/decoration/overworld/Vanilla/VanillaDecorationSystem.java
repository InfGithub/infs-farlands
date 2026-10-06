package com.inf.farlands.terrain.system.decoration.overworld.Vanilla;

import com.inf.farlands.terrain.DecorationSystem;
import com.inf.farlands.terrain.decorationFiller.DecorationRandom;
import com.inf.farlands.terrain.decorationFiller.DecorationRegion;
import com.inf.farlands.terrain.decorationFiller.ScopedStructureManager;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.system.common.overworld.Vanilla.VanillaFamilySeed;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * vanilla 装饰系统：biome json 的地物列表加结构放置，体是 ChunkGenerator.applyBiomeDecoration，
 * 随机性取本系统的 seed。
 *
 * <p>区域必须是交进来的 region：它按读域交出主线程备好的句柄，而 StructureManager 必须 scope 到
 * 同一个 region，否则结构放置的取数会落到活世界的阻塞取数上。
 *
 * <p>放置用的随机源在 applyBiomeDecoration 的方法体内创建，种子经 {@link DecorationRandom} 侧信道
 * 交进去，所以 set 与调用必须同线程，clear 走 finally。未选中本系统时侧信道无人设置，构造点退回
 * vanilla 的唯一种子。
 *
 * <p>无状态：全部局部构造，实例随 level 走、可跨线程共享。
 */
public final class VanillaDecorationSystem implements DecorationSystem {

    /** 声明：seed 用各族共用的空框取值源，见 {@link VanillaFamilySeed}。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(VanillaFamilySeed.seedParam());

    private final long seed;

    public VanillaDecorationSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    @Override
    public void applyDecoration(ServerLevel level, LevelChunk center, DecorationRegion region) {
        DecorationRandom.set(this.seed);
        try {
            level.getChunkSource().getGenerator().applyBiomeDecoration(region, center,
                    ScopedStructureManager.of(level, region));
        } finally {
            DecorationRandom.clear();
        }
    }
}
