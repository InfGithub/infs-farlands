package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import net.minecraft.util.RandomSource;

/**
 * beta 1.7.3 地形密度公式用的五通道八度噪声。
 *
 * <pre>{@code
 *   0  lower terrain limit   16 octaves 3D
 *   1  upper terrain limit   16 octaves 3D
 *   2  blend weight           8 octaves 3D
 *   3  temperature proxy     10 octaves 2D xz
 *   4  humidity proxy        16 octaves 2D xz
 * }</pre>
 *
 * <p>构造按 beta 原序消费同一个随机流。sand 与 stone 两组不参与地形，但必须占位消费：少消费一组
 * 会让其后各组的八度实例取到别的随机值，通道 3 与 4 随之改变，也就是换地形。
 */
public final class BetaTerrainNoise {

    private static final double FREQ_XZ = 684.412;
    private static final int O_LIMIT = 16;
    private static final int O_BLEND = 8;
    private static final int O_TEMP = 10;

    private final LegacyPerlinNoise[][] octaves;

    public BetaTerrainNoise(long seed) {
        RandomSource random = RandomSource.create(seed);
        LegacyPerlinNoise[][] all = new LegacyPerlinNoise[7][];
        all[0] = createOctaves(random, O_LIMIT);
        all[1] = createOctaves(random, O_LIMIT);
        all[2] = createOctaves(random, O_BLEND);
        all[3] = createOctaves(random, 4);
        all[4] = createOctaves(random, 4);
        all[5] = createOctaves(random, O_TEMP);
        all[6] = createOctaves(random, O_LIMIT);
        this.octaves = new LegacyPerlinNoise[][] { all[0], all[1], all[2], all[5], all[6] };
    }

    private static LegacyPerlinNoise[] createOctaves(RandomSource random, int count) {
        LegacyPerlinNoise[] arr = new LegacyPerlinNoise[count];
        for (int i = 0; i < count; i++) {
            arr[i] = new LegacyPerlinNoise(random);
        }
        return arr;
    }

    /**
     * 取某通道的八度叠加值。
     *
     * @param channel 0=lower，1=upper，2=blend，3=temp，4=humidity
     * @param y       通道 3 与 4 传 beta 的固定切片 10.0，其余传 cell Y
     */
    public double sample(int channel, double x, double y, double z) {
        double fx;
        double fy;
        double fz;
        switch (channel) {
            case 2:
                fy = FREQ_XZ / 160.0;
                fx = FREQ_XZ / 80.0;
                fz = FREQ_XZ / 80.0;
                break;
            case 3:
                fy = 1.0;
                fx = 1.121;
                fz = 1.121;
                break;
            case 4:
                fy = 1.0;
                fx = 200.0;
                fz = 200.0;
                break;
            default:
                fy = FREQ_XZ;
                fx = FREQ_XZ;
                fz = FREQ_XZ;
                break;
        }

        double sum = 0.0;
        double freq = 1.0;
        for (LegacyPerlinNoise noise : this.octaves[channel]) {
            sum += noise.generateNoise(x * fx * freq, y * fy * freq, z * fz * freq) / freq;
            freq /= 2.0;
        }
        return sum;
    }
}
