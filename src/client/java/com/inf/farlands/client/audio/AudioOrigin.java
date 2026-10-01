package com.inf.farlands.client.audio;

import net.minecraft.world.phys.Vec3;

/**
 * 交给 OpenAL 的坐标系的公共锚点。
 *
 * <p>
 * 听者位置与音源位置都表达为相对同一个锚点的偏移。原因是 OpenAL 只收 float：一个位置的误差由它的
 * 绝对值决定，而不是由它与听者的距离决定。绝对值到 2^31 时 ulp 是 128 格，16 格内的音源与听者被
 * 压到同一个 float 上，OpenAL 算出的距离恒为 0，声音失去方位与衰减。减掉一个近身锚点后，两侧都只
 * 剩几十到五十万格的量级。
 *
 * <p>
 * 两侧必须用同一个锚点，否则等效偏移会整体平移一个格边长。因此锚点不做共享变量，而是一个纯函数：
 * 听者那一侧用收到的 transform 位置求值，音源那一侧用同一帧的相机位置求值，两侧输入同一个值，输出
 * 必然相同。
 *
 * <p>
 * 格边长取 2 的幂，量化只用除法与取整，不引入第二个舍入源。取 2^19 时该量级 ulp 是 0.0625 格，
 * 可听距离内不足 0.5 度方向误差，而玩家要移动 524288 格才会换一次格。
 */
public final class AudioOrigin {

    private AudioOrigin() {
    }

    /** 格边长，524288 = 2^19。 */
    public static final double CELL_SIZE = 524288.0D;

    /** 位置所在格的格原点。 */
    public static Vec3 quantize(Vec3 position) {
        return new Vec3(
                Math.floor(position.x / CELL_SIZE) * CELL_SIZE,
                Math.floor(position.y / CELL_SIZE) * CELL_SIZE,
                Math.floor(position.z / CELL_SIZE) * CELL_SIZE);
    }

    /** 位置相对它自己所在格的偏移，即该位置减去该格的格原点。 */
    public static Vec3 shift(Vec3 position) {
        Vec3 anchor = quantize(position);
        return new Vec3(position.x - anchor.x, position.y - anchor.y, position.z - anchor.z);
    }

    /** 两个锚点是否同格。量化结果是精确值，逐分量比较即可。 */
    public static boolean sameAnchor(Vec3 a, Vec3 b) {
        return a.x == b.x && a.y == b.y && a.z == b.z;
    }
}
