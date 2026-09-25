package com.inf.farlands.terrain.system.terrain.noise.overworld.Beta173;

import net.minecraft.util.RandomSource;

/**
 * beta 1.7.3 {@code NoiseGeneratorPerlin} 的逐字节移植。
 *
 * <p>用 {@code (int)} 强转做 floor，在 2^31 处溢出并饱和到 int 边界，远处坐标因此落回同一条置换
 * 通道，产生边境之地那段重复地形。这个截断是特性，不是缺陷，改动它等于换地形。
 */
public final class LegacyPerlinNoise {

    private final int[] permutations = new int[512];
    public final double xCoord;
    public final double yCoord;
    public final double zCoord;

    public LegacyPerlinNoise(RandomSource random) {
        this.xCoord = random.nextDouble() * 256.0;
        this.yCoord = random.nextDouble() * 256.0;
        this.zCoord = random.nextDouble() * 256.0;

        for (int i = 0; i < 256; i++) {
            this.permutations[i] = i;
        }

        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i) + i;
            int tmp = this.permutations[i];
            this.permutations[i] = this.permutations[j];
            this.permutations[j] = tmp;
            this.permutations[i + 256] = this.permutations[i];
        }
    }

    public double generateNoise(double x, double y, double z) {
        double x1 = x + this.xCoord;
        double y1 = y + this.yCoord;
        double z1 = z + this.zCoord;
        int xi = (int) x1;
        int yi = (int) y1;
        int zi = (int) z1;
        if (x1 < (double) xi) {
            xi--;
        }
        if (y1 < (double) yi) {
            yi--;
        }
        if (z1 < (double) zi) {
            zi--;
        }

        int i = xi & 255;
        int j = yi & 255;
        int k = zi & 255;
        x1 -= xi;
        y1 -= yi;
        z1 -= zi;
        double sx = x1 * x1 * x1 * (x1 * (x1 * 6.0 - 15.0) + 10.0);
        double sy = y1 * y1 * y1 * (y1 * (y1 * 6.0 - 15.0) + 10.0);
        double sz = z1 * z1 * z1 * (z1 * (z1 * 6.0 - 15.0) + 10.0);

        int a = this.permutations[i] + j;
        int aa = this.permutations[a] + k;
        int ab = this.permutations[a + 1] + k;
        int b = this.permutations[i + 1] + j;
        int ba = this.permutations[b] + k;
        int bb = this.permutations[b + 1] + k;

        return lerp(sz,
                lerp(sy,
                        lerp(sx, grad(this.permutations[aa], x1, y1, z1),
                                grad(this.permutations[ba], x1 - 1.0, y1, z1)),
                        lerp(sx, grad(this.permutations[ab], x1, y1 - 1.0, z1),
                                grad(this.permutations[bb], x1 - 1.0, y1 - 1.0, z1))),
                lerp(sy,
                        lerp(sx, grad(this.permutations[aa + 1], x1, y1, z1 - 1.0),
                                grad(this.permutations[ba + 1], x1 - 1.0, y1, z1 - 1.0)),
                        lerp(sx, grad(this.permutations[ab + 1], x1, y1 - 1.0, z1 - 1.0),
                                grad(this.permutations[bb + 1], x1 - 1.0, y1 - 1.0, z1 - 1.0))));
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h != 12 && h != 14 ? z : x);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
