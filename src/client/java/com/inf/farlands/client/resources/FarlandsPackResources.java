package com.inf.farlands.client.resources;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;

/**
 * 把 mod 自身的 assets 作为最高优先级资源包注入。
 *
 * <p>
 * 本 mod 不使用 Fabric API，mod jar 内的 assets 不会被挂载成资源包：Fabric 的资源挂载由
 * {@code fabric-resource-loader-v0} 提供，而原版的
 * {@code VanillaPackResourcesBuilder.pushClasspathResources} 只处理 {@code file} scheme，
 * {@code jar} scheme 被静默跳过，所以生产环境 mod 的资源从来不在资源栈里。这里自己构造
 * {@link PackResources}，在 {@code PackRepository.openAllSelected} 的返回值末尾追加，见
 * {@code PackRepositoryMixin}。
 *
 * <p>
 * 两种部署形态由探针 URL 的 scheme 分派：dev 的 {@code build/resources/main} 是目录，走
 * {@link PathPackResources}；生产的 mod jar 是 zip，走 {@link FilePackResources}（自管
 * {@code ZipFile}，不碰 zipfs，因此不与原版共享的 jar {@code FileSystem} 生命周期耦合，
 * 也不会因别人 close 而失效）。
 *
 * <p>
 * 每次调用都新建实例。{@code ReloadableResourceManager.createReload} 会先关闭上一批 packs，
 * 而 {@code MultiPackResourceManager.close} 关闭列表内全部 packs，复用单例会让第二次重载
 * 用到已关闭的句柄。
 */
public final class FarlandsPackResources {

    /**
     * 探针资源。刻意放在 namespace 目录内部：{@code PathPackResources.getNamespaces} 遍历
     * {@code assets/} 的直接子项时，对非目录项会打 "Non-directory entry ... rejecting" 告警。
     */
    private static final String PROBE = "/assets/infs-farlands/.mcassetsroot";

    private static final String PACK_ID = "infs-farlands-resources";

    private FarlandsPackResources() {
    }

    /** 构造一个指向 mod 资源根的新 {@link PackResources}。探针缺失视为打包错误，抛异常而非静默返回。 */
    public static PackResources create() {
        URI probe = probeUri();
        PackLocationInfo location = new PackLocationInfo(
                PACK_ID,
                Component.literal("Inf's Farlands"),
                PackSource.BUILT_IN,
                Optional.empty());
        String scheme = probe.getScheme();
        if ("file".equals(scheme)) {
            // dev：探针落在 build/resources/main/assets/infs-farlands/ 下，上溯三层即资源根
            return new PathPackResources.PathResourcesSupplier(resourceRoot(Paths.get(probe)))
                    .openPrimary(location);
        }
        if ("jar".equals(scheme)) {
            // 生产：jar:file:/path/mod.jar!/assets/... 截到 !/ 之前即 jar 本身
            String spec = probe.getSchemeSpecificPart();
            int bang = spec.indexOf("!/");
            if (bang < 0) {
                throw new IllegalStateException("farlands: malformed jar probe uri " + probe);
            }
            File jar = new File(URI.create(spec.substring(0, bang)));
            return new FilePackResources.FileResourcesSupplier(jar).openPrimary(location);
        }
        throw new IllegalStateException("farlands: unsupported resource scheme " + scheme + " at " + probe);
    }

    private static URI probeUri() {
        var url = FarlandsPackResources.class.getResource(PROBE);
        if (url == null) {
            throw new IllegalStateException("farlands: resource probe missing from mod jar: " + PROBE);
        }
        try {
            return url.toURI();
        } catch (Exception e) {
            throw new IllegalStateException("farlands: cannot resolve probe uri " + url, e);
        }
    }

    /** 探针路径上溯三层：{@code <root>/assets/<namespace>/<probe>} -> {@code <root>}。 */
    private static Path resourceRoot(Path probe) {
        return probe.getParent().getParent().getParent();
    }
}
