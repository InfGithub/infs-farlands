package com.inf.farlands.client.mixin.pack;

import java.util.ArrayList;
import java.util.List;

import com.inf.farlands.client.resources.FarlandsPackResources;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 mod 自身资源追加到资源包列表末尾。
 *
 * <p>
 * {@code PackRepository.openAllSelected} 是客户端两条资源加载路径的共同出口：{@code Minecraft}
 * 构造器里的首屏加载与 {@code reloadResourcePacks} 的手动重载。追加在返回值而非放进
 * {@code available}/{@code selected}，因此不出现在资源包 UI、玩家无法关闭，也不需要
 * {@code pack.mcmeta}。
 *
 * <p>
 * 优先级：{@code FallbackResourceManager.pushInternal} 追加到列表末尾，而
 * {@code getResource} 从末尾倒序取首个命中，所以列表末尾即最高优先级，本包会压过 vanilla
 * 与玩家选择的所有资源包。
 *
 * <p>
 * {@code openAllSelected} 的返回值是 ImmutableList，必须复制后再追加。
 *
 * <p>
 * 副作用：服务端的 {@code WorldLoader} 与客户端的 {@code KnownPacksManager} 也调用本方法，
 * 但它们的 {@code MultiPackResourceManager} 用 {@code SERVER_DATA}，本包不提供 {@code data}
 * 命名空间，因此不会参与解析，也不会改变服务端数据包行为。
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
