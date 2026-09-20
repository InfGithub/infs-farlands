package com.inf.farlands.util.config;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 单个配置项。静态持有，读写经 Gson 分派。
 *
 * <p>值类型由 {@link #type()} 给出：int/long/short/byte/float/double/String/Enum/Boolean。
 *
 * <p>除值本身外还承载三项由 builder 声明、读盘期使用的元数据：
 * <ul>
 * <li>{@link Constraint}：值约束。只校验配置文件里<b>显式声明</b>的值；
 *     <b>不</b>校验 {@code "auto"} 派生出来的结果——后者是运行时算出的，不是用户输入。
 *     把两者混在一起，{@code range} 就既不是护栏也不是文档，还会制造用户无法修复的启动失败。
 * <li>{@link Range}：约束的 JSON 表示，只供落盘给手改配置的人对照。
 * <li>{@code autoValue}：{@code "auto"} 的取值器，由声明处传入，读盘期只求值一次。
 * </ul>
 *
 * @param <T> 值类型
 */
public final class ConfigEntry<T> {

    /** 值约束。越界抛 {@link IllegalArgumentException}，消息只含值域，文件与条目名由 ConfigFile 补。 */
    public interface Constraint<T> {
        void check(T value);
    }

    /** 约束的 JSON 表示，落盘为 {@code {"min": …, "max": …}}；声明侧没范围时为 null。 */
    public record Range(Number min, Number max) {
    }

    /** 配置项名：仅允许 a-zA-Z0-9_-。 */
    public static boolean isValidName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return name.length() > 0;
    }

    private final String name;
    private final Map<String, String> notes; // 注释来源 -> 注释
    private final Class<?> type; // 值类型，枚举用 Enum 类
    private final T defaultValue;
    private final Range range;
    private final Constraint<T> constraint;
    private final Supplier<T> autoValue;
    private final boolean defaultAuto;
    private T value;

    /**
     * 当前值是否读自 {@code "auto"} 字面量。回写时据此决定写 {@code "auto"} 还是写解析后的值——
     * 少了这个状态，任何一次回填重写都会把 {@code "auto"} 静默冻结成数字。
     */
    private boolean auto;

    public ConfigEntry(String name, Map<String, String> notes, Class<?> type, T defaultValue,
            Range range, Constraint<T> constraint, Supplier<T> autoValue, boolean defaultAuto) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid config entry name: %s".formatted(name));
        }
        this.name = name;
        this.notes = Map.copyOf(notes);
        this.type = type;
        this.defaultValue = defaultValue;
        this.range = range;
        this.constraint = constraint;
        this.autoValue = autoValue;
        this.defaultAuto = defaultAuto;
        this.value = defaultValue;
    }

    public String name() {
        return name;
    }

    public Map<String, String> notes() {
        return notes;
    }

    public Class<?> type() {
        return type;
    }

    public T defaultValue() {
        return defaultValue;
    }

    /** 默认值是否为 auto。为 true 时 {@link #defaultValue()} 为 null。 */
    public boolean isDefaultAuto() {
        return defaultAuto;
    }

    public Range range() {
        return range;
    }

    public Constraint<T> constraint() {
        return constraint;
    }

    /** {@code "auto"} 的取值器；未声明时为 null。 */
    public Supplier<T> autoValue() {
        return autoValue;
    }

    public T get() {
        return value;
    }

    public void set(T v) {
        this.value = v;
    }

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean v) {
        this.auto = v;
    }
}
