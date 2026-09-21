package com.inf.farlands.mixin.fix.audio;

import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 声音包坐标编码从 int 加宽到 long。
 *
 * <p>vanilla 的构造器把世界坐标写成 {@code (int)(x * 8.0)}，{@code d2i} 对超范围饱和，
 * 于是 |coord| 超过 2^28，即约 2.68 亿时三个坐标全部变成 {@code Integer.MAX_VALUE}。
 * 客户端解回的位置与真实位置无关，且距离被算成 0，表现为无声或方位全错。
 * 本 mod 的可玩范围是 ±2^31，这一段必须在范围内。
 *
 * <p>线上格式与 vanilla 唯一差别是三个坐标各占 8 字节，包增大 12 字节。两端必须同为
 * 本 mod，否则解码错位。
 *
 * <p>三条路径都要落到自己的 long 字段，缺一条都会让 getX/getY/getZ 给出错值：服务端构造器
 * 由 {@link #farlands$storeLongCoords} 写入，客户端反序列化由 {@link #farlands$readLongAsInt}
 * 写入。客户端那条尤其容易漏，因为它不经过服务端构造器：只读不写会让三个字段恒为 0，
 * 声音被算在世界原点，表现为方位与衰减全错。
 *
 * <p>旧的 {@code int x/y/z} 字段保留但不再被读，{@code @Shadow} 与描述符因此不必改动。
 */
@Mixin(ClientboundSoundPacket.class)
public abstract class ClientboundSoundPacketMixin {

    @Shadow
    @Final
    private Holder<SoundEvent> sound;

    @Shadow
    @Final
    private SoundSource source;

    @Shadow
    @Final
    private float volume;

    @Shadow
    @Final
    private float pitch;

    @Shadow
    @Final
    private long seed;

    @Unique
    private long farlands$x8;

    @Unique
    private long farlands$y8;

    @Unique
    private long farlands$z8;

    /**
     * 解码时的坐标序号：0 = x，1 = y，2 = z。
     *
     * <p>构造器里三次 {@code readInt} 的字节码顺序固定为 x、y、z，且三次调用之间没有其它
     * 对同一缓冲区的读取，所以按调用序分派即可。不在构造器 HEAD 处归零：HEAD 位于
     * {@code super()} 之前，Mixin 禁止在那里触碰实例字段。每个新实例的字段默认即 0。
     */
    @Unique
    private int farlands$coordIndex;

    /**
     * 服务端构造：坐标为 double，乘 8 后直接放 long，不做饱和。
     *
     * <p>不 @Shadow 私有 int x/y/z：真实坐标就在本构造器的形参里，{@code (int)(x * 8.0)}
     * 的饱和已经在 vanilla 构造器体内发生，从字段读回来拿到的只会是饱和值。
     */
    @Inject(method = "<init>(Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;DDDFFJ)V", at = @At("RETURN"))
    private void farlands$storeLongCoords(Holder<SoundEvent> sound, SoundSource source, double x, double y,
            double z, float volume, float pitch, long seed, CallbackInfo ci) {
        this.farlands$x8 = (long) (x * 8.0);
        this.farlands$y8 = (long) (y * 8.0);
        this.farlands$z8 = (long) (z * 8.0);
    }

    /**
     * 客户端构造：坐标从 4 字节改读 8 字节，并写进自己的 long 字段。
     *
     * <p>target 的 owner 必须是 {@code RegistryFriendlyByteBuf}：字节码里三次调用写作
     * {@code invokevirtual RegistryFriendlyByteBuf.readInt:()I}，owner 是编译期类型而不是该
     * 方法真正的声明类 {@code FriendlyByteBuf}。写成声明类会扫描到 0 个目标。
     *
     * <p>解码结果必须写进 long 字段。客户端反序列化只走本构造器，不经过服务端那条路，不写
     * 就恒为 0，声音会被算在世界原点。
     *
     * <p>不在构造器 HEAD 处归零 {@code coordIndex}：HEAD 位于 {@code super()} 之前，Mixin
     * 禁止在那里触碰实例字段。每个新实例的字段默认即 0，无需显式重置。
     */
    @Redirect(method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/RegistryFriendlyByteBuf;readInt()I"))
    private int farlands$readLongAsInt(RegistryFriendlyByteBuf buffer) {
        long v = buffer.readLong();
        switch (this.farlands$coordIndex) {
            case 0 -> this.farlands$x8 = v;
            case 1 -> this.farlands$y8 = v;
            default -> this.farlands$z8 = v;
        }
        this.farlands$coordIndex++;
        return (int) v;
    }

    /** 编码：三个坐标写 long，其余字段逐字照抄 vanilla。 */
    @Overwrite
    private void write(RegistryFriendlyByteBuf buffer) {
        SoundEvent.STREAM_CODEC.encode(buffer, this.sound);
        buffer.writeEnum(this.source);
        buffer.writeLong(this.farlands$x8);
        buffer.writeLong(this.farlands$y8);
        buffer.writeLong(this.farlands$z8);
        buffer.writeFloat(this.volume);
        buffer.writeFloat(this.pitch);
        buffer.writeLong(this.seed);
    }

    @Overwrite
    public double getX() {
        return (double) this.farlands$x8 / 8.0;
    }

    @Overwrite
    public double getY() {
        return (double) this.farlands$y8 / 8.0;
    }

    @Overwrite
    public double getZ() {
        return (double) this.farlands$z8 / 8.0;
    }

}
