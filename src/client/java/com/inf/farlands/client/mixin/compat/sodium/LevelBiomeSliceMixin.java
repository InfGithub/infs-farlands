package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.FarlandsConstant;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.caffeinemc.mods.sodium.client.world.biome.LevelBiomeSlice;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium 0.9.2+mc26.1.2 版本绑定，升级即碎。
 *
 * <p>
 * update 用 origin 的最小方块端点减 16 当自己的基准：blockX = minBlockX() - 16，三轴各一。这个基准
 * 与 getBiome 的入参相减得相对坐标，所以它必须是真实的方块坐标；而它减 16 会比邻域那两族（减 2、
 * 减 2）更早下溢：minBlockX() 低到 -2147483632 时减 16 就落到 int 下界之外，回绕成一个正数。
 * 基准翻正之后 getBiome 的 relBlockX 是负的二十亿，QuartPos.fromBlock 与 dataArrayIndex 随即在长度
 * 1728 的数组上越界。
 *
 * <p>
 * 与另外两族不同，这一族只有减 16、没有加，且只有最小端点三处。夹的边界要按 16 反推，不能沿用
 * 加减 2 那一族的边界。
 */
@Pseudo
@Mixin(LevelBiomeSlice.class)
public abstract class LevelBiomeSliceMixin {

    /** 使原地那次 -16 停在 int 内的最小端点。 */
    @Unique
    private static final int farlands$minEndpoint16 = FarlandsConstant.MAX_PLAYABLE_BLOCK;

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockX()I"))
    private int farlands$satMinBlockX(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint16 + 16 ? farlands$minEndpoint16 + 16 : value;
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockY()I"))
    private int farlands$satMinBlockY(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint16 + 16 ? farlands$minEndpoint16 + 16 : value;
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/SectionPos;minBlockZ()I"))
    private int farlands$satMinBlockZ(SectionPos self, Operation<Integer> original) {
        int value = original.call(self);
        return value < farlands$minEndpoint16 + 16 ? farlands$minEndpoint16 + 16 : value;
    }
}
