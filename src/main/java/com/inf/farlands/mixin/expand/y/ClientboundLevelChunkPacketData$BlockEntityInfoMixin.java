package com.inf.farlands.mixin.expand.y;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 方块实体世界 Y 的线上编码从 short 加宽到 int。
 *
 * vanilla 的 BlockEntityInfo 用 readShort/writeShort 编码方块实体世界 Y，|Y| 超过 32767
 * 就被截断，例如 32767 之上的取模、负向同样。客户端拿到截断值后会把方块实体建到垃圾
 * 坐标，永不渲染，且不报错。
 *
 * 线上格式改成 4 字节 int。原 y 字段仍是截断值、保留不用，完整值存进注入的字段，由
 * ClientboundLevelChunkPacketDataMixin 重建位置时读取。
 *
 * BlockEntityInfo 是私有静态嵌套类，只能 targets 字符串引用。readShort/writeShort 的
 * 描述符与 1.21.1 逐字相同，字节码里各只有一处。
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public abstract class ClientboundLevelChunkPacketData$BlockEntityInfoMixin {

    /** 完整的世界 Y。读包时写入，重建位置时读取。 */
    @Unique
    private int yCorrect;

    @Redirect(method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/RegistryFriendlyByteBuf;readShort()S"))
    private short farlands$readFullY(RegistryFriendlyByteBuf buffer) {
        this.yCorrect = buffer.readInt();
        return (short) this.yCorrect;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/RegistryFriendlyByteBuf;writeShort(I)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf farlands$writeFullY(RegistryFriendlyByteBuf buffer, int value) {
        return buffer.writeInt(value);
    }
}
