package com.inf.farlands.terrain.system.common.overworld.Oct;

import com.inf.farlands.FarlandsConstant;

import net.minecraft.core.Holder;
import net.minecraft.data.worldgen.TerrainProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * 默认变体的 overworld 密度链，节点全用 Oct 包下的自研类，坐标经 OctScale 缩放。
 *
 * <p>链的形状逐条抄自 vanilla NoiseRouterData 的 overworld 家族：只把坐标消费节点换成自研类、
 * 把 Y 锚点按 scaleY 缩放。large_biomes 与 amplified 两个变体不复刻，需要在别处拦。
 *
 * <p>scale 全 1 时各节点与 vanilla 逐位一致，整条链因此与 vanilla 同源。
 */
public final class OctOverworldDensity {

    /** 与 vanilla 的 BLENDING_FACTOR 同值。 */
    private static final double BLENDING_FACTOR = 10.0;

    /** 矿脉门的 Y 范围，取自 vanilla OreVeinifier.VeinType 的两个取值 0/50 与 -60/-8。 */
    private static final double VEIN_MIN_Y = -60.0;
    private static final double VEIN_MAX_Y = 50.0;

    /** 地表预扫描的步长，等于 overworld 的 cell 高。 */
    private static final int SURFACE_CELL_HEIGHT = NoiseSettings.create(-64, 384, 1, 2).getCellHeight();

    private final OctScale scale;
    private final OctNoiseSource noises;

    private final DensityFunction y;
    private final DensityFunction shiftX;
    private final DensityFunction shiftZ;
    private final DensityFunction continents;
    private final DensityFunction erosion;
    private final DensityFunction ridges;
    private final DensityFunction ridgesFolded;
    private final DensityFunction jaggedNoise;
    private final DensityFunction offset;
    private final DensityFunction factor;
    private final DensityFunction depth;
    private final DensityFunction slopedCheese;

    private final DensityFunction barrierNoise;
    private final DensityFunction fluidLevelFloodednessNoise;
    private final DensityFunction fluidLevelSpreadNoise;
    private final DensityFunction lavaNoise;
    private final DensityFunction temperature;
    private final DensityFunction vegetation;
    private final DensityFunction preliminarySurfaceLevel;
    private final DensityFunction finalDensity;
    private final DensityFunction veinToggle;
    private final DensityFunction veinRidged;
    private final DensityFunction veinGap;

    public OctOverworldDensity(OctScale scale, OctNoiseSource noises) {
        this.scale = scale;
        this.noises = noises;

        this.y = new OctYClampedGradient(-FarlandsConstant.MAX_BLOCK, FarlandsConstant.MAX_BLOCK,
                -FarlandsConstant.MAX_BLOCK, FarlandsConstant.MAX_BLOCK);
        this.shiftX = flatCache(cache2d(new OctShiftNoise.ShiftA(noises.holder(Noises.SHIFT), scale)));
        this.shiftZ = flatCache(cache2d(new OctShiftNoise.ShiftB(noises.holder(Noises.SHIFT), scale)));
        this.continents = flatCache(shifted(Noises.CONTINENTALNESS));
        this.erosion = flatCache(shifted(Noises.EROSION));
        DensityFunction ridge = flatCache(shifted(Noises.RIDGE));
        this.ridges = ridge;
        this.ridgesFolded = peaksAndValleys(ridge);
        this.jaggedNoise = noises.noise(Noises.JAGGED, 1500.0, 0.0);

        this.offset = buildOffset();
        this.factor = buildFactor();
        this.depth = offsetToDepth(offset);
        DensityFunction jaggedness = mul(buildJaggedness(), jaggedNoise.halfNegative());
        this.slopedCheese = add(noiseGradientDensity(factor, add(depth, jaggedness)), noises.blendedNoise());

        this.barrierNoise = noises.noise(Noises.AQUIFER_BARRIER, 1.0, 0.5);
        this.fluidLevelFloodednessNoise = noises.noise(Noises.AQUIFER_FLUID_LEVEL_FLOODEDNESS, 1.0, 0.67);
        this.fluidLevelSpreadNoise = noises.noise(Noises.AQUIFER_FLUID_LEVEL_SPREAD, 1.0, 0.7142857142857143);
        this.lavaNoise = noises.noise(Noises.AQUIFER_LAVA, 1.0, 1.0);
        this.temperature = shifted(Noises.TEMPERATURE);
        this.vegetation = shifted(Noises.VEGETATION);

        this.preliminarySurfaceLevel = buildPreliminarySurfaceLevel();
        this.finalDensity = buildFinalDensity();

        double veinMin = VEIN_MIN_Y * scale.y();
        double veinMax = VEIN_MAX_Y * scale.y();
        this.veinToggle = yLimitedInterpolatable(y, noises.noise(Noises.ORE_VEININESS, 1.5, 1.5),
                veinMin, veinMax, 0.0);
        DensityFunction veinA = yLimitedInterpolatable(y, noises.noise(Noises.ORE_VEIN_A, 4.0, 4.0),
                veinMin, veinMax, 0.0).abs();
        DensityFunction veinB = yLimitedInterpolatable(y, noises.noise(Noises.ORE_VEIN_B, 4.0, 4.0),
                veinMin, veinMax, 0.0).abs();
        this.veinRidged = add(constant(-0.08F), max(veinA, veinB));
        this.veinGap = noises.noise(Noises.ORE_GAP, 1.0, 1.0);
    }

    // ---- router 字段 ----

    public DensityFunction barrierNoise() {
        return barrierNoise;
    }

    public DensityFunction fluidLevelFloodednessNoise() {
        return fluidLevelFloodednessNoise;
    }

    public DensityFunction fluidLevelSpreadNoise() {
        return fluidLevelSpreadNoise;
    }

    public DensityFunction lavaNoise() {
        return lavaNoise;
    }

    public DensityFunction temperature() {
        return temperature;
    }

    public DensityFunction vegetation() {
        return vegetation;
    }

    public DensityFunction continents() {
        return continents;
    }

    public DensityFunction erosion() {
        return erosion;
    }

    public DensityFunction depth() {
        return depth;
    }

    public DensityFunction ridges() {
        return ridges;
    }

    public DensityFunction preliminarySurfaceLevel() {
        return preliminarySurfaceLevel;
    }

    public DensityFunction finalDensity() {
        return finalDensity;
    }

    public DensityFunction veinToggle() {
        return veinToggle;
    }

    public DensityFunction veinRidged() {
        return veinRidged;
    }

    public DensityFunction veinGap() {
        return veinGap;
    }

    // ---- 组装 ----

    private DensityFunction buildOffset() {
        return splineWithBlending(
                add(constant(-0.50375F), spline(TerrainProvider.overworldOffset(
                        coordinate(continents), coordinate(erosion), coordinate(ridgesFolded), false))),
                DensityFunctions.blendOffset());
    }

    private DensityFunction buildFactor() {
        return splineWithBlending(
                spline(TerrainProvider.overworldFactor(coordinate(continents), coordinate(erosion),
                        coordinate(ridges), coordinate(ridgesFolded), false)),
                constant(BLENDING_FACTOR));
    }

    private DensityFunction buildJaggedness() {
        return splineWithBlending(
                spline(TerrainProvider.overworldJaggedness(coordinate(continents), coordinate(erosion),
                        coordinate(ridges), coordinate(ridgesFolded), false)),
                zero());
    }

    private DensityFunction buildPreliminarySurfaceLevel() {
        DensityFunction cachedFactor = cache2d(factor);
        DensityFunction cachedOffset = cache2d(offset);
        DensityFunction upperBound = remap(
                add(mul(constant(0.2734375), cachedFactor.invert()), mul(constant(-1.0), cachedOffset)),
                1.5, -1.5, -64.0 * scale.y(), 320.0 * scale.y());
        upperBound = upperBound.clamp(-40.0 * scale.y(), 320.0 * scale.y());
        DensityFunction density = add(
                slideOverworld(add(noiseGradientDensity(cachedFactor, offsetToDepth(cachedOffset)),
                        constant(-0.703125)).clamp(-64.0, 64.0)),
                constant(-0.390625));
        return new OctFindTopSurface(density, upperBound, -64.0 * scale.y(), SURFACE_CELL_HEIGHT);
    }

    private DensityFunction buildFinalDensity() {
        DensityFunction surfaceWithEntrances = min(slopedCheese, mul(constant(5.0), entrances()));
        DensityFunction caves = rangeChoice(slopedCheese, -1000000.0, 1.5625, surfaceWithEntrances,
                underground());
        return min(postProcess(slideOverworld(caves)), noodle());
    }

    private DensityFunction underground() {
        DensityFunction layerNoiseSource = noises.noise(Noises.CAVE_LAYER, 1.0, 8.0);
        DensityFunction layerizedCavernsFunction = mul(constant(4.0), layerNoiseSource.square());
        DensityFunction cheese = noises.noise(Noises.CAVE_CHEESE, 1.0, 0.6666666666666666);
        DensityFunction solidifedCheeseWithTopSlide = add(
                add(constant(0.27), cheese).clamp(-1.0, 1.0),
                add(constant(1.5), mul(constant(-0.64), slopedCheese)).clamp(0.0, 0.5));
        DensityFunction baseCaveDensity = add(layerizedCavernsFunction, solidifedCheeseWithTopSlide);
        DensityFunction undergroundSubtractions = min(
                min(baseCaveDensity, entrances()), add(spaghetti2D(), spaghettiRoughness()));
        DensityFunction pillarsWithoutCutoff = pillars();
        DensityFunction pillars = rangeChoice(pillarsWithoutCutoff, -1000000.0, 0.03,
                constant(-1000000.0), pillarsWithoutCutoff);
        return max(undergroundSubtractions, pillars);
    }

    private DensityFunction spaghettiRoughness() {
        DensityFunction roughnessNoise = noises.noise(Noises.SPAGHETTI_ROUGHNESS, 1.0, 1.0);
        DensityFunction roughnessModulator = mappedNoise(noises.holder(Noises.SPAGHETTI_ROUGHNESS_MODULATOR),
                1.0, 1.0, 0.0, -0.1);
        return cacheOnce(mul(roughnessModulator, add(roughnessNoise.abs(), constant(-0.4))));
    }

    private DensityFunction spaghetti2D() {
        DensityFunction rarityModulator = noises.noise(Noises.SPAGHETTI_2D_MODULATOR, 2.0, 1.0);
        DensityFunction cave = weirdScaledSampler(rarityModulator, noises.holder(Noises.SPAGHETTI_2D),
                OctWeirdScaledSampler.RarityValueMapper.TYPE2);
        DensityFunction elevationModulator = mappedNoise(noises.holder(Noises.SPAGHETTI_2D_ELEVATION),
                1.0, 0.0, Math.floorDiv(-64, 8), 8.0);
        DensityFunction thicknessModulator = cacheOnce(mappedNoise(noises.holder(Noises.SPAGHETTI_2D_THICKNESS),
                2.0, 1.0, -0.6, -1.3));
        DensityFunction slopedSpaghetti = add(elevationModulator,
                new OctYClampedGradient(-64.0 * scale.y(), 320.0 * scale.y(), 8.0, -40.0)).abs();
        DensityFunction layerRidged = add(slopedSpaghetti, thicknessModulator).cube();
        DensityFunction caveNoise = add(cave, mul(constant(0.083), thicknessModulator));
        return max(caveNoise, layerRidged).clamp(-1.0, 1.0);
    }

    private DensityFunction entrances() {
        DensityFunction rarityModulator = cacheOnce(noises.noise(Noises.SPAGHETTI_3D_RARITY, 2.0, 1.0));
        DensityFunction thicknessModulator = mappedNoise(noises.holder(Noises.SPAGHETTI_3D_THICKNESS),
                1.0, 1.0, -0.065, -0.088);
        DensityFunction cave1 = weirdScaledSampler(rarityModulator, noises.holder(Noises.SPAGHETTI_3D_1),
                OctWeirdScaledSampler.RarityValueMapper.TYPE1);
        DensityFunction cave2 = weirdScaledSampler(rarityModulator, noises.holder(Noises.SPAGHETTI_3D_2),
                OctWeirdScaledSampler.RarityValueMapper.TYPE1);
        DensityFunction spaghetti3DFunction = add(max(cave1, cave2), thicknessModulator).clamp(-1.0, 1.0);
        DensityFunction bigEntranceNoiseSource = noises.noise(Noises.CAVE_ENTRANCE, 0.75, 0.5);
        DensityFunction bigEntrancesFunction = add(
                add(bigEntranceNoiseSource, constant(0.37)),
                new OctYClampedGradient(-10.0 * scale.y(), 30.0 * scale.y(), 0.3, 0.0));
        return cacheOnce(min(bigEntrancesFunction, add(spaghettiRoughness(), spaghetti3DFunction)));
    }

    private DensityFunction noodle() {
        DensityFunction noodleToggle = yLimitedInterpolatable(y, noises.noise(Noises.NOODLE, 1.0, 1.0),
                -60.0 * scale.y(), 320.0 * scale.y(), -1.0);
        DensityFunction noodleThickness = yLimitedInterpolatable(y,
                mappedNoise(noises.holder(Noises.NOODLE_THICKNESS), 1.0, 1.0, -0.05, -0.1),
                -60.0 * scale.y(), 320.0 * scale.y(), 0.0);
        DensityFunction ridgeA = yLimitedInterpolatable(y,
                noises.noise(Noises.NOODLE_RIDGE_A, 2.6666666666666665, 2.6666666666666665),
                -60.0 * scale.y(), 320.0 * scale.y(), 0.0);
        DensityFunction ridgeB = yLimitedInterpolatable(y,
                noises.noise(Noises.NOODLE_RIDGE_B, 2.6666666666666665, 2.6666666666666665),
                -60.0 * scale.y(), 320.0 * scale.y(), 0.0);
        DensityFunction noodleRidged = mul(constant(1.5), max(ridgeA.abs(), ridgeB.abs()));
        return rangeChoice(noodleToggle, -1000000.0, 0.0, constant(64.0),
                add(noodleThickness, noodleRidged));
    }

    private DensityFunction pillars() {
        DensityFunction pillarNoiseSource = noises.noise(Noises.PILLAR, 25.0, 0.3);
        DensityFunction rarenessModulator = mappedNoise(noises.holder(Noises.PILLAR_RARENESS),
                1.0, 1.0, 0.0, -2.0);
        DensityFunction thicknessModulator = mappedNoise(noises.holder(Noises.PILLAR_THICKNESS),
                1.0, 1.0, 0.0, 1.1);
        DensityFunction pillarsWithRareness = add(mul(pillarNoiseSource, constant(2.0)), rarenessModulator);
        return cacheOnce(mul(pillarsWithRareness, thicknessModulator.cube()));
    }

    private DensityFunction slideOverworld(DensityFunction caves) {
        return slide(caves,
                -64.0 * scale.y(), 384.0 * scale.y(),
                80.0 * scale.y(), 64.0 * scale.y(), -0.078125,
                0.0, 24.0 * scale.y(), 0.1171875);
    }

    private static DensityFunction slide(DensityFunction caves, double minY, double height,
            double topStartY, double topEndY, double topTarget,
            double bottomStartY, double bottomEndY, double bottomTarget) {
        DensityFunction topFactor = new OctYClampedGradient(minY + height - topStartY,
                minY + height - topEndY, 1.0, 0.0);
        DensityFunction value = DensityFunctions.lerp(topFactor, topTarget, caves);
        DensityFunction bottomFactor = new OctYClampedGradient(minY + bottomStartY,
                minY + bottomEndY, 0.0, 1.0);
        return DensityFunctions.lerp(bottomFactor, bottomTarget, value);
    }

    private static DensityFunction postProcess(DensityFunction slide) {
        return mul(interpolated(blendDensity(slide)), constant(0.64)).squeeze();
    }

    private static DensityFunction splineWithBlending(DensityFunction spline, DensityFunction blendingTarget) {
        return flatCache(cache2d(DensityFunctions.lerp(DensityFunctions.blendAlpha(), blendingTarget, spline)));
    }

    private static DensityFunction noiseGradientDensity(DensityFunction factor,
            DensityFunction depthWithJaggedness) {
        return mul(constant(4.0), mul(depthWithJaggedness, factor).quarterNegative());
    }

    private DensityFunction offsetToDepth(DensityFunction offset) {
        return add(new OctYClampedGradient(-64.0 * scale.y(), 320.0 * scale.y(), 1.5, -1.5), offset);
    }

    private DensityFunction yLimitedInterpolatable(DensityFunction input, DensityFunction whenInRange,
            double minYInclusive, double maxYInclusive, double whenOutOfRange) {
        return interpolated(rangeChoice(input, minYInclusive, maxYInclusive + 1.0, whenInRange,
                constant(whenOutOfRange)));
    }

    private static DensityFunction peaksAndValleys(DensityFunction weirdness) {
        return mul(
                add(add(weirdness.abs(), constant(-0.6666666666666666)).abs(), constant(-0.3333333333333333)),
                constant(-3.0));
    }

    private static DensityFunction remap(DensityFunction input, double fromMin, double fromMax,
            double toMin, double toMax) {
        double factor = (toMax - toMin) / (fromMax - fromMin);
        double offset = toMin - fromMin * factor;
        return add(mul(input, constant(factor)), constant(offset));
    }

    private DensityFunction shifted(ResourceKey<NormalNoise.NoiseParameters> key) {
        return new OctShiftedNoise(shiftX, zero(), shiftZ, 0.25, 0.0, noises.holder(key), scale);
    }

    private DensityFunction weirdScaledSampler(DensityFunction input, DensityFunction.NoiseHolder noise,
            OctWeirdScaledSampler.RarityValueMapper mapper) {
        return new OctWeirdScaledSampler(input, noise, mapper, scale);
    }

    private DensityFunction mappedNoise(DensityFunction.NoiseHolder noise, double xzScale, double yScale,
            double minTarget, double maxTarget) {
        return mapFromUnitTo(new OctNoise(noise, xzScale, yScale, scale), minTarget, maxTarget);
    }

    private static DensityFunction mapFromUnitTo(DensityFunction function, double min, double max) {
        return add(constant((min + max) / 2.0), mul(function, constant((max - min) / 2.0)));
    }

    private static DensityFunctions.Spline.Coordinate coordinate(DensityFunction function) {
        return new DensityFunctions.Spline.Coordinate(Holder.direct(function));
    }

    // ---- 直接委托 vanilla 的结构节点 ----

    private static DensityFunction interpolated(DensityFunction function) {
        return DensityFunctions.interpolated(function);
    }

    private static DensityFunction flatCache(DensityFunction function) {
        return DensityFunctions.flatCache(function);
    }

    private static DensityFunction cache2d(DensityFunction function) {
        return DensityFunctions.cache2d(function);
    }

    private static DensityFunction cacheOnce(DensityFunction function) {
        return DensityFunctions.cacheOnce(function);
    }

    private static DensityFunction blendDensity(DensityFunction function) {
        return DensityFunctions.blendDensity(function);
    }

    private static DensityFunction add(DensityFunction first, DensityFunction second) {
        return DensityFunctions.add(first, second);
    }

    private static DensityFunction mul(DensityFunction first, DensityFunction second) {
        return DensityFunctions.mul(first, second);
    }

    private static DensityFunction min(DensityFunction first, DensityFunction second) {
        return DensityFunctions.min(first, second);
    }

    private static DensityFunction max(DensityFunction first, DensityFunction second) {
        return DensityFunctions.max(first, second);
    }

    private static DensityFunction rangeChoice(DensityFunction input, double minInclusive, double maxExclusive,
            DensityFunction whenInRange, DensityFunction whenOutOfRange) {
        return DensityFunctions.rangeChoice(input, minInclusive, maxExclusive, whenInRange, whenOutOfRange);
    }

    private static DensityFunction spline(
            net.minecraft.util.CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline) {
        return DensityFunctions.spline(spline);
    }

    private static DensityFunction constant(double value) {
        return DensityFunctions.constant(value);
    }

    private static DensityFunction zero() {
        return DensityFunctions.zero();
    }
}
