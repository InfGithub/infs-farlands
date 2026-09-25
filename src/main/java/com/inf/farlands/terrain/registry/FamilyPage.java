package com.inf.farlands.terrain.registry;

import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * 创建世界页签对应的维度页。四族的默认系统表按本枚举分派。
 *
 * <p>
 * 放在 main 源集是因为默认系统表在 {@code terrain/registry} 下，不能让主源集引用客户端类型。
 */
public enum FamilyPage {
    OVERWORLD("overworld"),
    THE_NETHER("the_nether"),
    THE_END("the_end");

    private final String path;

    FamilyPage(String path) {
        this.path = path;
    }

    /** 该页对应维度的 id，与 systems.dat 里以维度为键的那一层同形。 */
    public Identifier dimensionId() {
        return Identifier.withDefaultNamespace(this.path);
    }

    /** 按维度 id 反查页；不属于这三页的维度返回空。 */
    public static Optional<FamilyPage> ofDimension(Identifier dimension) {
        for (FamilyPage page : values()) {
            if (page.dimensionId().equals(dimension)) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }
}
