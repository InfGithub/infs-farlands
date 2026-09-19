package com.inf.farlands.util.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.inf.farlands.InfsFarlands;

/**
 * 配置入口：静态持有全部配置项。
 * 注册在 static 块，InfsFarlands.onInitialize 调 {@link #init()} 读文件。
 */
public final class Config {

    private static final Map<String, ConfigEntry<?>> ENTRIES = new LinkedHashMap<>();

    /** 幂等标志：init() 只读一次文件，多个配置类 static 块重复调用无害。 */
    private static boolean initialized;

    private Config() {
    }

    public static <T> ConfigEntry<T> register(String name, Class<?> type, T defaultValue,
            Map<String, String> notes) {
        ConfigEntry<T> e = new ConfigEntry<>(name, notes, type, defaultValue);
        ENTRIES.put(name, e);
        return e;
    }

    /**
     * 读文件。幂等：首次调用读磁盘并填充 ConfigEntry；之后直接返回。
     */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ConfigFile.load(
                Path.of(System.getProperty("user.dir"), "config", "%s.json".formatted(
                        InfsFarlands.MOD_ID)),
                Map.of("en_us", "Inf's Farlands configuration", "zh_cn", "Inf's Farlands 配置文件"),
                ENTRIES);
    }

}
