package com.inf.farlands.client.mixin.fix.xz;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;

/**
 * 天气渲染的极端坐标修复，两个方法各自整段覆写。
 *
 * <p>
 * extractRenderState 的窗口界原版按 int 计算。cameraBlockY + radius 在相机 Y 接近 int 上界时回绕，
 * 回绕值让 y1 变成大负数，列实例的竖直跨度可达数十亿格；循环上界 cameraBlockZ + radius 回绕则有两种
 * 后果，恰好等于 Integer.MAX_VALUE 时 z++ 永不越界而卡死，越过时整段不画。这里把界先升 long 再夹回
 * int 可表示范围，循环计数用 long 以免自增回绕，丢掉的只是坐标空间之外的列，那些列没有方块。
 *
 * <p>
 * renderInstances 的 V 原版取 bottomY * 0.25F。bottomY 在 2.1e9 量级转 float 的量化步进已到 64，
 * 远大于纹理 0.25 的 V 步进，V 塌缩成常量，纹理竖直方向被拉平。V 改为相对相机方块 Y 的差值再乘
 * 0.25F，差值是小值，精度足够；代价是纹理相位随相机 Y 滑动，雨滴纹理 4 格周期的竖直条纹按周期平移。
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

    @Shadow
    @Final
    private float[] columnSizeX;

    @Shadow
    @Final
    private float[] columnSizeZ;

    @Shadow
    private WeatherEffectRenderer.ColumnInstance createRainColumnInstance(RandomSource random, int ticks, int x,
            int bottomY, int topY, int z, int lightCoords, float partialTicks) {
        throw new AbstractMethodError();
    }

    @Shadow
    private WeatherEffectRenderer.ColumnInstance createSnowColumnInstance(RandomSource random, int ticks, int x,
            int bottomY, int topY, int z, int lightCoords, float partialTicks) {
        throw new AbstractMethodError();
    }

    @Shadow
    private Precipitation getPrecipitationAt(Level level, BlockPos pos) {
        throw new AbstractMethodError();
    }

    @Overwrite
    public void extractRenderState(Level level, int ticks, float partialTicks, Vec3 cameraPos,
            WeatherRenderState renderState) {
        renderState.intensity = level.getRainLevel(partialTicks);
        if (renderState.intensity <= 0.0F) {
            return;
        }
        renderState.radius = Minecraft.getInstance().options.weatherRadius().get();
        int cameraBlockX = Mth.floor(cameraPos.x);
        int cameraBlockY = Mth.floor(cameraPos.y);
        int cameraBlockZ = Mth.floor(cameraPos.z);
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.createThreadLocalInstance();

        int zLo = (int) Math.max((long) cameraBlockZ - renderState.radius, Integer.MIN_VALUE);
        int zHi = (int) Math.min((long) cameraBlockZ + renderState.radius, Integer.MAX_VALUE);
        int xLo = (int) Math.max((long) cameraBlockX - renderState.radius, Integer.MIN_VALUE);
        int xHi = (int) Math.min((long) cameraBlockX + renderState.radius, Integer.MAX_VALUE);
        for (long zL = zLo; zL <= zHi; zL++) {
            int z = (int) zL;
            for (long xL = xLo; xL <= xHi; xL++) {
                int x = (int) xL;
                int terrainHeight = level.getHeight(Types.MOTION_BLOCKING, x, z);
                long y0L = Math.max((long) cameraBlockY - renderState.radius, (long) terrainHeight);
                long y1L = Math.max((long) cameraBlockY + renderState.radius, (long) terrainHeight);
                int y0 = (int) Math.max(y0L, Integer.MIN_VALUE);
                int y1 = (int) Math.min(y1L, Integer.MAX_VALUE);
                if (y1 - y0 != 0) {
                    Precipitation precipitation = this.getPrecipitationAt(level, mutablePos.set(x, cameraBlockY, z));
                    if (precipitation != Precipitation.NONE) {
                        int seed = x * x * 3121 + x * 45238971 ^ z * z * 418711 + z * 13761;
                        random.setSeed(seed);
                        int lightSampleY = Math.max(cameraBlockY, terrainHeight);
                        int lightCoords = LevelRenderer.getLightCoords(level, mutablePos.set(x, lightSampleY, z));
                        if (precipitation == Precipitation.RAIN) {
                            renderState.rainColumns.add(this.createRainColumnInstance(random, ticks, x, y0, y1, z,
                                    lightCoords, partialTicks));
                        } else if (precipitation == Precipitation.SNOW) {
                            renderState.snowColumns.add(this.createSnowColumnInstance(random, ticks, x, y0, y1, z,
                                    lightCoords, partialTicks));
                        }
                    }
                }
            }
        }
    }

    @Overwrite
    private void renderInstances(VertexConsumer builder, List<WeatherEffectRenderer.ColumnInstance> columns,
            Vec3 cameraPos, float maxAlpha, int radius, float intensity) {
        if (columns.isEmpty()) {
            return;
        }
        float radiusSq = radius * radius;
        int cameraBlockY = Mth.floor(cameraPos.y);
        for (WeatherEffectRenderer.ColumnInstance column : columns) {
            float relativeX = (float) (column.x() + 0.5 - cameraPos.x);
            float relativeZ = (float) (column.z() + 0.5 - cameraPos.z);
            float distanceSq = (float) Mth.lengthSquared(relativeX, relativeZ);
            float alpha = Mth.lerp(Math.min(distanceSq / radiusSq, 1.0F), maxAlpha, 0.5F) * intensity;
            int color = ARGB.white(alpha);
            int index = (column.z() - Mth.floor(cameraPos.z) + 16) * 32 + column.x() - Mth.floor(cameraPos.x) + 16;
            float halfSizeX = this.columnSizeX[index] / 2.0F;
            float halfSizeZ = this.columnSizeZ[index] / 2.0F;
            float x0 = relativeX - halfSizeX;
            float x1 = relativeX + halfSizeX;
            float y1 = (float) (column.topY() - cameraPos.y);
            float y0 = (float) (column.bottomY() - cameraPos.y);
            float z0 = relativeZ - halfSizeZ;
            float z1 = relativeZ + halfSizeZ;
            float u0 = column.uOffset() + 0.0F;
            float u1 = column.uOffset() + 1.0F;
            float v0 = (column.bottomY() - cameraBlockY) * 0.25F + column.vOffset();
            float v1 = (column.topY() - cameraBlockY) * 0.25F + column.vOffset();
            builder.addVertex(x0, y1, z0).setUv(u0, v0).setColor(color).setLight(column.lightCoords());
            builder.addVertex(x1, y1, z1).setUv(u1, v0).setColor(color).setLight(column.lightCoords());
            builder.addVertex(x1, y0, z1).setUv(u1, v1).setColor(color).setLight(column.lightCoords());
            builder.addVertex(x0, y0, z0).setUv(u0, v1).setColor(color).setLight(column.lightCoords());
        }
    }
}
