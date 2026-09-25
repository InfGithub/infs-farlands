package com.inf.farlands.terrain.registry;

/**
 * 创建世界页签对应的维度页。四族的默认系统表按本枚举分派。
 *
 * <p>
 * 放在 main 源集是因为默认系统表在 {@code terrain/registry} 下，不能让主源集引用客户端类型。
 */
public enum FamilyPage {
    OVERWORLD,
    THE_NETHER,
    THE_END
}
