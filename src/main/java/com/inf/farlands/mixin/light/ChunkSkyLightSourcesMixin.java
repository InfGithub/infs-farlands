package com.inf.farlands.mixin.light;

import com.inf.farlands.FarlandsConfig;
import com.inf.farlands.light.IColumnMasks;
import com.inf.farlands.light.ISkyLightSourcesAccessor;
import com.inf.farlands.util.window.WindowedChunk;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkSkyLightSources 的窗口化 + 侧信道改写。
 *
 * <p>
 * 26.1.2 相对 1.21.1 的结构变化：
 * <ul>
 * <li>{@code findLowestSourceY(chunk, topSectionIndex, x, z)} 改为从
 * {@code getHighestFilledSectionIndex()} 起、按 section 索引倒序扫，内部调
 * {@code isEdgeOccluded(topState, bottomState)} **去掉 BlockGetter 与坐标**</li>
 * <li>{@code isEdgeOccluded} 现在直接用 {@code getLightDampening()} 判定，不再读方块</li>
 * <li>{@code fillFrom} 重写为「取最高非空 section 索引 + 16×16 列扫描」</li>
 * <li>minY 的取值：{@code getMinBuildHeight()-1} -> {@code getMinY()-1}；
 * 位数 = {@code ceillog2((getMaxY()+1) - minY + 1)}，与原式等价
 * （maxBuildHeight 开边界 ↔ maxY 闭边界，同一最后方块 Y）</li>
 * </ul>
 */
@Mixin(ChunkSkyLightSources.class)
public class ChunkSkyLightSourcesMixin implements ISkyLightSourcesAccessor {

    /** fillFrom 完成标志，用于区分 fill 中与空气：内容判断无法区分 fillFrom 后无源的真空气。 */
    @Unique
    private volatile boolean farlandsFillFromDone;

    /**
     * 侧信道存储，index -> 绝对 sourceY：替代 vanilla BitStorage，其 int value 上限
     * 2^31-1 无法覆盖 ±2.14B stored = sourceY - minY 在负 Y 为负、极端正 Y 超界。
     * 未设置 = minY，表示无光源，与 BitStorage 默认语义等价。heightmap 字段保留但不再
     * 使用；fixBits/@ModifyArg 改的是构造创建的 BitStorage 位数，无害。
     *
     * volatile int[256]：fillFrom 单写，gen 线程构建新数组 volatile 发布 -> light
     * 线程播种读，happens-before 由 lightChunk 队列链保证。构造 RETURN 填 minY，
     * 使 fillFrom 前 getHighestLowestSourceY 的 MIN_VALUE 检查正确。
     *
     * 数组在本注入器内创建，不能写成字段初始化器：Mixin 靠行号切分「字段初始化器 /
     * 构造器方法体」，本类隐式构造器的行号跨度是 [类声明行, return 行]，字段初始化器
     * 的行落在跨度内 → 被判为方法体 → Initialiser 为空 → applyInitialisers 静默早退
     * → 字段永不赋值（RETURN 回调里 Arrays.fill 读到 null）。注入器方法体不经该机制。
     */
    @Unique
    private volatile int[] source;

    @Inject(method = "<init>(Lnet/minecraft/world/level/LevelHeightAccessor;)V", at = @At("RETURN"))
    private void initSourceArray(LevelHeightAccessor level, CallbackInfo ci) {
        int[] arr = new int[256];
        java.util.Arrays.fill(arr, this.minY);
        this.source = arr;
    }

    @Shadow
    @Final
    @Mutable
    private BitStorage heightmap;

    @Shadow
    private int minY;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutablePos1;

    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutablePos2;

    private static LevelHeightAccessor capturedLevel;

    @Inject(method = "<init>(Lnet/minecraft/world/level/LevelHeightAccessor;)V", at = @At("HEAD"))
    private static void captureLevel(LevelHeightAccessor level, CallbackInfo ci) {
        capturedLevel = level;
    }

    @ModifyArg(method = "<init>(Lnet/minecraft/world/level/LevelHeightAccessor;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/SimpleBitStorage;<init>(II)V"), index = 0)
    private int fixBits(int overflowedBits) {
        long span = (long) capturedLevel.getMaxY() - (long) capturedLevel.getMinY() + 2L;
        return 64 - Long.numberOfLeadingZeros(span - 1L);
    }

    // 侧信道读写：覆盖 vanilla 的 heightmap 存取

    @Overwrite
    private void set(int index, int value) {
        this.source[index] = value;
    }

    @Overwrite
    private int get(int index) {
        return this.source[index];
    }

    @Overwrite
    public int getHighestLowestSourceY() {
        int i = this.minY;
        for (int v : this.source) {
            if (v > i) {
                i = v;
            }
        }
        return i == this.minY ? Integer.MIN_VALUE : i;
    }

    @Overwrite
    private void fill(int value) {
        // fillFrom 前 getHighestLowestSourceY 读旧数组 旧数组内容 = 上次 fill
        // 结果，fill(minY) 语义 = 全部无源 = minY，兼容。
        int[] arr = new int[256];
        java.util.Arrays.fill(arr, value);
        this.source = arr;
    }

    // fillFrom：窗口语义重落

    /**
     * 覆盖 fillFrom：直接用 allSections 计算真实最高非空 section 与遮挡，
     * 不依赖 getHighestFilledSectionIndex/getSection，遵循窗口数组语义。
     */
    @Overwrite
    public void fillFrom(ChunkAccess chunk) {
        if (!(chunk instanceof WindowedChunk wc)) {
            this.fill(this.minY);
            this.farlandsFillFromDone = true;
            return;
        }
        Map<Integer, LevelChunkSection> all = wc.windowedAllSections();
        // 预计算非空 section 升序数组，一次遍历；findLowestSourceYAll 倒序访问。
        // 降序访问实际非空 section：循环次数 = section 数，与跨度无关。
        int[] buf = new int[all.size()];
        int n = 0;
        for (Integer sy : all.keySet()) {
            LevelChunkSection s = all.get(sy);
            if (s != null && !s.hasOnlyAir()) {
                buf[n++] = sy;
            }
        }
        if (n == 0) {
            this.fill(this.minY);
            this.farlandsFillFromDone = true;
            return;
        }
        int[] nonEmpty = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
        java.util.Arrays.sort(nonEmpty);
        // gen 线程写 -> light 线程播种读，happens-before 由 lightChunk 队列链保证。
        int[] arr = new int[256];
        for (int gx = 0; gx < 16; gx++) {
            for (int gz = 0; gz < 16; gz++) {
                int lowest = this.findLowestSourceYAll(all, nonEmpty, gx, gz);
                arr[gx + gz * 16] = Math.max(lowest, this.minY);
            }
        }
        this.source = arr;
        this.farlandsFillFromDone = true;
    }

    @Override
    public boolean farlands$isFillFromDone() {
        return this.farlandsFillFromDone;
    }

    /** AIR 常量缓存。 */
    @Unique
    private static final BlockState FARLANDS_AIR = Blocks.AIR.defaultBlockState();

    /**
     * 复刻 vanilla findLowestSourceY 的遮挡语义，但用 allSections 的绝对 section Y；
     * 非空 section 倒序 + 列掩码位跳。
     *
     * <p>
     * 26.1.2 的 isEdgeOccluded 只吃两个 BlockState（无 level/坐标），above 态由
     * prevY 相邻性维护：行相邻则用上一行的 state，否则视为 AIR（跳过的空气行/空洞
     * section 使 prevY 与当前行不相邻）。
     */
    @Unique
    private int findLowestSourceYAll(Map<Integer, LevelChunkSection> all, int[] nonEmpty, int x, int z) {
        int prevY = Integer.MIN_VALUE; // 哨兵：首访问行上方无访问行 -> above=AIR
        BlockState prevState = null;
        for (int idx = nonEmpty.length - 1; idx >= 0; idx--) {
            int sy = nonEmpty[idx];
            LevelChunkSection sec = all.get(sy);
            if (sec == null) {
                continue;
            }
            // 列级非空掩码，LevelChunkSectionMixin 维护：bit k = 该行非空气 免逐格
            // getBlockState 的 palette 查找。
            short[] masks = ((IColumnMasks) sec).farlands$ensureColumnMasks();
            int m = masks[x + z * 16] & 0xFFFF; // short 符号扩展抹高位，bit 仅 0-15
            while (m != 0) {
                // 自顶向下取最高 1 位 LZCNT 单指令
                int k = 31 - Integer.numberOfLeadingZeros(m);
                m ^= 1 << k; // 清当前位
                int y = sy * 16 + k;
                BlockState above = prevY == y + 1 ? prevState : FARLANDS_AIR;
                BlockState state = sec.getBlockState(x, k, z);
                if (isEdgeOccluded(above, state)) {
                    return y + 1;
                }
                prevY = y;
                prevState = state;
            }
        }
        return this.minY;
    }

    /** 26.1.2 的两态签名（原 1.21.1 为 BlockGetter + 两个 BlockPos + 两个 state）。 */
    @Shadow
    private static boolean isEdgeOccluded(BlockState aboveState, BlockState state) {
        return false;
    }

    /**
     * cap maxCapIter 格：vanilla while 循环从 pos 向下到 minY 侧信道开启后破坏
     * 2.14B 方块、update 的 i == k 触发且下方无遮挡时，会从 2.14B 扫到 minY =
     * 21 亿次，每格读方块 -> OOM。正常高度 limitY = minY，行为不变；负 Y pos < minY
     * -> 循环天然不执行。cap 后区间内无遮挡 -> 返回 minY，保守：该列近似无光源，
     * 比 OOM 好；后续再放置恢复正常。
     */
    @Overwrite
    private int findLowestSourceBelow(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        BlockPos.MutableBlockPos mp1 = new BlockPos.MutableBlockPos().set(pos);
        BlockPos.MutableBlockPos mp2 = new BlockPos.MutableBlockPos().setWithOffset(pos,
                net.minecraft.core.Direction.DOWN);
        BlockState lowerState = state;
        int limitY = (int) Math.max((long) pos.getY() - FarlandsConfig.maxCapIter, (long) this.minY);
        while (mp2.getY() >= limitY) {
            BlockState belowState = level.getBlockState(mp2);
            if (isEdgeOccluded(lowerState, belowState)) {
                return mp1.getY();
            }
            lowerState = belowState;
            mp1.set(mp2);
            mp2.move(net.minecraft.core.Direction.DOWN);
        }
        return this.minY;
    }
}
