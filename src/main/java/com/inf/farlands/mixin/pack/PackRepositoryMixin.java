package com.inf.farlands.mixin.pack;

import java.util.ArrayList;
import java.util.List;

import com.inf.farlands.util.resources.FarlandsPackResources;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 mod 自身的包追加到已选包列表末尾。
 *
 * <p>
 * {@code PackRepository.openAllSelected} 是四条加载路径的共同出口：客户端首屏（{@code Minecraft}
 * 构造器）与手动重载（{@code reloadResourcePacks}）、服务端数据包加载
 * （{@code WorldLoader.PackConfig.createResourceManager}）、以及专用服务器的同一条路径。
 * 注入在返回值而非放进 {@code available}/{@code selected}，因此不出现在资源包/数据包 UI、
 * 玩家无法关闭，也不需要 {@code pack.mcmeta}。
 *
 * <p>
 * 优先级：{@code FallbackResourceManager.pushInternal} 追加到列表末尾，而
 * {@code getResource} 从末尾倒序取首个命中，所以列表末尾即最高优先级，本包会压过 vanilla
 * 与玩家选择的所有包。
 *
 * <p>
 * {@code openAllSelected} 的返回值是 ImmutableList，必须复制后再追加。
 *
 * <p>
 * 放在 main 源集：数据包是服务端资源，专用服务器不加载 client 源集的混入。客户端行为不变，
 * 因为 main 源集两端都加载。
 *
 * <p>
 * 已知语义边界：safeMode 下 {@code MinecraftServer.configurePackRepository} 只选 vanilla，
 * 但本注入发生在它之后，所以本包在 safeMode 下仍会加载。这与"safeMode 并不禁用 mod 代码"
 * 一致——本包是 mod 的一部分，且不来自玩家磁盘。
 */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {

    @Inject(method = "openAllSelected", at = @At("RETURN"), cancellable = true)
    private void farlands$appendSelfResources(CallbackInfoReturnable<List<PackResources>> cir) {
        List<PackResources> packs = new ArrayList<>(cir.getReturnValue());
        packs.add(FarlandsPackResources.create());
        cir.setReturnValue(packs);
    }
}
