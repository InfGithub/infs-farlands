package com.inf.farlands.terrain.registry;

/**
 * 一个维度页下的四个族。四张类型化注册表与四张默认表都按族各写一份，本枚举给它们一个共同的名字，
 * 供界面配置按键索引。
 */
public enum FamilyKind {
    BIOME,
    TERRAIN,
    SURFACE,
    CARVER
}
