package com.inf.farlands.mixin.terrain.surface;

import java.lang.reflect.Constructor;
import java.util.function.Function;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.util.window.WindowedChunk;
import com.inf.farlands.util.world.WorldBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 地表阶段的全高适配：列扫描下界从维度界改为「地表 + 1 − maxCapIter」，并丢弃 surface rule
 * 产出的基岩。
 *
 * <p>
 * vanilla 的 {@code buildSurface} 列扫描下界取 {@code protoChunk.getMinY()}，而
 * {@code ChunkAccess.getMinY()} 返回的是 {@code levelHeightAccessor.getMinY()}——即维度下界
 * （-64）。本 port 的地形不按维度高度生成：{@code AbstractNoiseFiller} 按窗口段构造
 * {@code NoiseSettings}（{@code new NoiseSettings(minSectionY * 16, …)}），且
 * {@code DimensionType} 的 Y 界已放宽到 ±MAX_BLOCK，所以密度函数在 -64 以下继续产出、
 * fill 也照填。于是维度下界 -64 既不是地形下界，作为扫描下界就漏掉整片地形。
 *
 * <p>
 * 为什么是 {@code @Overwrite} 而不是 {@code @Redirect}：下界
 * {@code endY = max(可玩高度下界, 地表 + 1 − maxCapIter)} 依赖每列的地表高度（方法内局部量
 * {@code height}），而 {@code @Redirect} 的 handler 只能拿到被调用对象，算不出这个值。
 * 26.1.2 的 class 文件也没有 LocalVariableTable（{@code javap -l} 零条），按名捕获局部量不可行。
 *
 * <p>
 * 下界的下限取 {@link WorldBounds#MIN_PLAYABLE_BLOCK} 而不是 {@code chunk.getMinY()}：既然
 * -64 以下有地形，那里也需要地表规则。上界仍是"地表往下 maxCapIter 格"，所以循环次数恒
 * ≤ maxCapIter——两种取值下差值都不会超过它（取地表侧时差为 512，取可玩下界侧时差更小），
 * 减法先转 long 防地表接近 int 上限时溢出。
 *
 * <p>
 * maxCapIter 出自 {@code findLowestSourceBelow} 的 cap 先例：那是一次 2.14B 高度上的 21 亿次
 * 循环，结果是 8GB 堆耗尽、GC 风暴导致 jstack/jmap attach 全部超时、只能靠二分定位。此处与
 * 它同构，故沿用同一个上界。
 *
 * <p>
 * <b>-64 以下被基岩填充是预期效果</b>：数据包 {@code overworld.json} 的 surface rule 第一条是
 * {@code vertical_gradient("bedrock_floor", true_at_and_below: above_bottom(0), …)}，其
 * {@code compute} 在 {@code blockY <= getMinGenY()}（= -64）时<b>无条件返回 true</b>
 * （{@code SurfaceRules:760-762}）。本 port 的地形可以延伸到 -64 以下（见上），扫描区间放宽后
 * 那一片的石头正好被刷成基岩，由它封住世界底——这是要的结果，不是缺陷。
 *
 * <p>
 * {@code SurfaceRules$Context} 与 {@code $SurfaceRule} 的 InnerClasses 属性是 protected
 * （class access_flags 实为 public，但 javac 按 InnerClasses 判断），所以这两个类型跨包不可
 * 引用。因此：Context 以 {@code Object} 持有、构造走反射（{@code Class.forName} + 缓存的
 * Constructor，且 Mixin 的 {@code @Invoker} 不支持 {@code <init>}），三个方法与
 * SurfaceRule.tryApply 改走 {@code @Invoker} 访问器（见 {@link SurfaceRules$ContextInvoker}
 * 与 {@link SurfaceRules$SurfaceRuleInvoker}），{@code ruleSource.apply} 经原始类型
 * {@code Function} 擦除调用。
 *
 * <p>
 * 与上一版本（{@code worldOverflowFix/SurfaceSystemMixin}）逐条对照后保留/调整的约束：
 * <ul>
 * <li>列扫描下界：<b>下界下限改为可玩高度下界</b>（上一版本用 {@code chunk.getMinBuildHeight()}，
 * 即维度下界，会漏掉 -64 以下的地形）；{@code maxCapIter} 上界与 long 减原样保留。</li>
 * <li>基岩填充：-64 以下被刷成基岩是预期效果（见上），故不过滤返回值。上一版本当前的
 * {@code SurfaceSystemMixin} 里也没有过滤——它把 {@code endY} 压在维度下界以上，从源头
 * 避开了那片填充，但也因此漏掉了 -64 以下的地形。</li>
 * <li>写入范围：保留 {@code scanRange}——比本 port 已放宽的 {@code isInsideBuildHeight}
 * 更窄，只允许写本次扫描区间。</li>
 * <li>无锁直写：仍然必要，且必须自带。{@code @Overwrite} 之后原版 {@code SurfaceSystem$1}
 * 不再被实例化，原本负责这条路径的 {@code SurfaceSystem$1Mixin} 随之失效，所以复制体内直接
 * 写 section（{@code useLocks=false}）。{@code chunk.setBlockState} 会走带锁重载
 * （PalettedContainer.acquire），与服务端线程的 section 写并发即 CTD。</li>
 * <li>列坐标：x/z 取列坐标 {@code colCoord}，只有 y 用入参——上一版本曾误把三参全取 y，
 * 写入堆到同一相对列，地表全乱。</li>
 * <li>{@code erodedBadlandsExtension} 的列扫描下界未动：它的起点 {@code startY} 是以海平面
 * 64 为基准的恶地柱几何（上限约 160），且有 {@code height <= startY} 前置条件——极端正 Y 下
 * 整个调用不执行，极端负 Y 下循环下界是绝对的 -64、次数有界，不需要跟随地表。</li>
 * </ul>
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemMixin {

    // ---- @Shadow：buildSurface 复制体实际使用的 SurfaceSystem 私有成员 ----

    @Shadow
    private BlockState defaultBlock;

    @Shadow
    private boolean isStone(BlockState state) {
        return false;
    }

    @Shadow
    private void erodedBadlandsExtension(BlockColumn blockColumn, int x, int z, int height, LevelHeightAccessor level) {
    }

    @Shadow
    private void frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn blockColumn,
            BlockPos.MutableBlockPos topWaterPos, int x, int z, int height) {
    }

    // ---- Context：类型不可引用，构造器 protected 且 @Invoker 不支持 <init> ----

    @Unique
    private static final Constructor<?> CTOR_CONTEXT;

    static {
        try {
            Class<?> contextClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
            CTOR_CONTEXT = contextClass.getDeclaredConstructor(
                    SurfaceSystem.class, RandomState.class, ChunkAccess.class, NoiseChunk.class,
                    Function.class, Registry.class, WorldGenerationContext.class);
            CTOR_CONTEXT.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static Object newContext(SurfaceSystem system, RandomState randomState,
            ChunkAccess chunk, NoiseChunk noiseChunk, Function<BlockPos, Holder<Biome>> biomeGetter,
            Registry<Biome> biomes, WorldGenerationContext generationContext) {
        try {
            return CTOR_CONTEXT.newInstance(system, randomState, chunk, noiseChunk, biomeGetter, biomes,
                    generationContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 复制 vanilla buildSurface（26.1.2 L66-163），5 处改动：
     * <ol>
     * <li>列扫描下界 endY：max(可玩高度下界, 地表+1 − maxCapIter)，long 减防溢出。</li>
     * <li>丢弃 surface rule 返回的基岩——bedrock_floor 的 vertical_gradient 在 getMinGenY()
     * 及以下全 true，不丢会盖住 -64 以下的地形。</li>
     * <li>BlockColumn.setBlock 范围：scanRange = [endY, height]（每列更新，匿名类数组捕获）。</li>
     * <li>写入改为无锁直写 section；x/z 取列坐标 colCoord，只有 y 用入参。</li>
     * <li>Context 与 SurfaceRule 走反射/invoker（类型不可引用）。</li>
     * </ol>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Overwrite
    public void buildSurface(
            RandomState randomState,
            BiomeManager biomeManager,
            Registry<Biome> biomes,
            boolean useLegacyRandom,
            WorldGenerationContext generationContext,
            ChunkAccess protoChunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource) {
        SurfaceSystem self = (SurfaceSystem) (Object) this;
        BlockPos.MutableBlockPos columnPos = new BlockPos.MutableBlockPos();
        ChunkPos chunkPos = protoChunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        // 扫描区间 [endY, height]，每列不同——匿名类不能捕获循环变量，用数组传递
        int[] scanRange = new int[2];
        // 写入用的列坐标：x/z 用列坐标，只有 y 用 setBlock 的入参
        int[] colCoord = new int[2];
        BlockColumn column = new BlockColumn() {
            @Override
            public BlockState getBlock(int blockY) {
                return protoChunk.getBlockState(columnPos.setY(blockY));
            }

            @Override
            public void setBlock(int blockY, BlockState state) {
                if (blockY >= scanRange[0] && blockY <= scanRange[1]) {
                    // 无锁直写 section（useLocks=false）——对齐 fill。走 chunk.setBlockState 会进
                    // 带锁重载（PalettedContainer.acquire），与服务端线程的 section 写并发即 CTD。
                    LevelChunkSection section = ((WindowedChunk) protoChunk).windowedAllSections().get(blockY >> 4);
                    if (section != null) {
                        section.setBlockState(colCoord[0] & 15, blockY & 15, colCoord[1] & 15, state, false);
                        if (!state.getFluidState().isEmpty()) {
                            // 直写绕过了 vanilla setBlockState 内部的 columnPos.setY，这里补齐
                            protoChunk.markPosForPostprocessing(columnPos.setY(blockY));
                        }
                    }
                }
            }

            @Override
            public String toString() {
                return "ChunkBlockColumn " + chunkPos;
            }
        };
        Object context = newContext(
                self, randomState, protoChunk, noiseChunk, biomeManager::getBiome, biomes, generationContext);
        // RuleSource extends Function<Context, SurfaceRule>，而 SurfaceRule 类型不可引用，
        // 故经原始类型 Function 擦除调用（返回 Object）
        Object rule = ((Function) ruleSource).apply(context);
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = minBlockX + x;
                int blockZ = minBlockZ + z;
                int startingHeight = protoChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                colCoord[0] = blockX;
                colCoord[1] = blockZ;
                columnPos.setX(blockX).setZ(blockZ);
                Holder<Biome> surfaceBiome = biomeManager.getBiome(
                        blockPos.set(blockX, useLegacyRandom ? 0 : startingHeight, blockZ));
                if (surfaceBiome.is(Biomes.ERODED_BADLANDS)) {
                    this.erodedBadlandsExtension(column, blockX, blockZ, startingHeight, protoChunk);
                }

                int height = protoChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                ((SurfaceRules$ContextInvoker) context).farlands$updateXZ(blockX, blockZ);
                int stoneAboveDepth = 0;
                int waterHeight = Integer.MIN_VALUE;
                int nextCeilingStoneY = Integer.MAX_VALUE;
                // 列扫描下界：下限取可玩高度下界（不是维度下界 chunk.getMinY()——本 port 的地形
                // 按窗口段生成，-64 以下也有地形），上界为"地表往下 maxCapIter 格"以保证有界。
                int endY = (int) Math.max((long) WorldBounds.MIN_PLAYABLE_BLOCK,
                        (long) height - (long) FarlandsConfig.maxCapIter);
                scanRange[0] = endY;
                scanRange[1] = height;

                for (int y = height; y >= endY; y--) {
                    BlockState old = column.getBlock(y);
                    if (old.isAir()) {
                        stoneAboveDepth = 0;
                        waterHeight = Integer.MIN_VALUE;
                    } else if (!old.getFluidState().isEmpty()) {
                        if (waterHeight == Integer.MIN_VALUE) {
                            waterHeight = y + 1;
                        }
                    } else {
                        if (nextCeilingStoneY >= y) {
                            nextCeilingStoneY = DimensionType.WAY_BELOW_MIN_Y;

                            for (int lookaheadY = y - 1; lookaheadY >= endY - 1; lookaheadY--) {
                                BlockState nextState = column.getBlock(lookaheadY);
                                if (!this.isStone(nextState)) {
                                    nextCeilingStoneY = lookaheadY + 1;
                                    break;
                                }
                            }
                        }

                        stoneAboveDepth++;
                        int stoneBelowDepth = y - nextCeilingStoneY + 1;
                        ((SurfaceRules$ContextInvoker) context).farlands$updateY(
                                stoneAboveDepth, stoneBelowDepth, waterHeight, blockX, y, blockZ);
                        if (old == this.defaultBlock) {
                            BlockState state = ((SurfaceRules$SurfaceRuleInvoker) rule)
                                    .farlands$tryApply(blockX, y, blockZ);
                            if (state != null) {
                                column.setBlock(y, state);
                            }
                        }
                    }
                }

                if (surfaceBiome.is(Biomes.FROZEN_OCEAN) || surfaceBiome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    this.frozenOceanExtension(
                            ((SurfaceRules$ContextInvoker) context).farlands$getMinSurfaceLevel(),
                            surfaceBiome.value(), column, blockPos, blockX, blockZ, startingHeight);
                }
            }
        }
    }
}
