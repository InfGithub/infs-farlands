package com.inf.farlands.terrain.system.terrain.noise.overworld.Oct;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.SystemArgs;
import com.inf.farlands.terrain.registry.SystemDefaultParams;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctNoiseHarvest;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctNoiseSource;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctOverworldDensity;
import com.inf.farlands.terrain.system.common.overworld.Oct.OctScale;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 主世界 Oct 噪声系统：不复用 vanilla 的密度节点，用 Oct 包下的自研链重建整条 router，三轴坐标按 scale 缩放。
 *
 * <p>
 * 噪声参数从传入的 vanilla router 采集，噪声实例用构造时的 seed 自行派生，全程公开 API。
 * 链只复刻默认变体，采集不全时整条回退 vanilla。vanilla router 每个 level 只有一份实例，
 * 本系统实例也是 per-level，所以重建结果按 vanilla router 身份缓存，每个 chunk 不会重算。
 */
public final class OctNoiseSystem implements NoiseSystem {

    /** 声明：三轴缩放默认不缩放；seed 只有一条映射，左端是空串。 */
    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofDouble("scaleX").define(1.0).build(),
            SystemParamSpec.ofDouble("scaleY").define(1.0).build(),
            SystemParamSpec.ofDouble("scaleZ").define(1.0).build(),
            SystemParamSpec.ofLong("seed")
                    .keyword("", () -> Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                            "createWorld.tab.infs-farlands.param.seed.random")
                    .build());

    private final OctScale scale;
    private final PositionalRandomFactory root;

    /** 重建结果与其对应的 vanilla router，写序先结果后键，读序先键后结果。 */
    private volatile NoiseRouter cachedRouter;
    private volatile NoiseRouter cachedVanilla;

    public OctNoiseSystem(SystemArgs args) {
        this.scale = new OctScale(args.getDouble("scaleX"), args.getDouble("scaleY"), args.getDouble("scaleZ"));
        this.root = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(args.getLong("seed")).forkPositional();
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return createRouter(router).finalDensity();
    }

    @Override
    public NoiseRouter createRouter(NoiseRouter vanilla) {
        NoiseRouter cached = this.cachedRouter;
        if (cached != null && this.cachedVanilla == vanilla) {
            return cached;
        }
        Map<ResourceKey<NormalNoise.NoiseParameters>, DensityFunction.NoiseHolder> table = OctNoiseHarvest
                .harvest(vanilla);
        OctNoiseSource source = new OctNoiseSource(scale, root, table);
        OctOverworldDensity density;
        try {
            density = new OctOverworldDensity(scale, source);
        } catch (RuntimeException e) {
            if (source.missing()) {
                return vanilla; // 采集不全，即非默认预设，整条回退
            }
            throw e;
        }
        NoiseRouter built = new NoiseRouter(
                density.barrierNoise(),
                density.fluidLevelFloodednessNoise(),
                density.fluidLevelSpreadNoise(),
                density.lavaNoise(),
                density.temperature(),
                density.vegetation(),
                density.continents(),
                density.erosion(),
                density.depth(),
                density.ridges(),
                density.preliminarySurfaceLevel(),
                density.finalDensity(),
                density.veinToggle(),
                density.veinRidged(),
                density.veinGap());
        this.cachedRouter = built;
        this.cachedVanilla = vanilla;
        return built;
    }

    /** 液面按 scaleY 抬升，与高度场同步。 */
    @Override
    public Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        int lavaLevel = (int) Math.floor(-54.0 * scale.y());
        int seaLevel = (int) Math.floor(settings.seaLevel() * scale.y());
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(lavaLevel, seaLevel) ? lava : water;
    }
}
