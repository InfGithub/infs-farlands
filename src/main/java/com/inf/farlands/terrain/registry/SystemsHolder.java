package com.inf.farlands.terrain.registry;

/**
 * 系统配置的取值入口，由 ServerLevel 注入。
 *
 * <p>配置是世界一份，只在第一个构造的 level 上解析一次，其余维度从已解析的那份取用，所以取值入口
 * 必须能从另一个 level 实例上读到，而不是每个维度各解析一份。
 */
public interface SystemsHolder {

    SystemsData systems();
}
