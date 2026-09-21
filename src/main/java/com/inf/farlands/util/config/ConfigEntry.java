package com.inf.farlands.util.config;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 单个配置项。静态持有，读写经 Gson 分派。
 *
 * <p>值类型由 {@link #type()} 给出：int/long/short/byte/float/double/String/Enum/Boolean。
 *
 * <p>除值本身外还承载由 builder 声明、读盘期使用的三类元数据：
 * <ul>
 * <li>{@link Constraint}：值约束。只校验配置文件里<b>显式声明</b>的值；
 *     <b>不</b>校验关键字派生出来的结果——后者是运行时算出的，不是用户输入。
 *     把两者混在一起，值域就既不是护栏也不是文档，还会制造用户无法修复的启动失败。
 * <li>{@code min} / {@code max}：约束的 JSON 表示，两侧各自独立、可缺一侧，
 *     只供落盘给手改配置的人对照。
 * <li>{@code keywords}：关键字名字到取值器与注释的映射，由声明处传入，读盘期按名反查。
 * </ul>
 *
 * @param <T> 值类型
 */
public final class ConfigEntry<T> {

    /** 值约束。越界抛 {@link IllegalArgumentException}，消息只含值域，文件与条目名由 ConfigFile 补。 */
    public interface Constraint<T> {
        void check(T value);
    }

    /** 一份关键字声明：取值器与它自己的注释。 */
    public record Keyword<T>(Supplier<T> supplier, Map<String, String> notes) {
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
    private final Map<String, Keyword<T>> keywords;
    /** 默认值命中关键字时记其名字，否则 null。 */
    private final String defaultKeyword;
    private final T min;
    private final T max;
    private final Constraint<T> constraint;
    private T value;

    /**
     * 当前值命中的关键字名。回写时据此写回关键字名而不是解析后的值——
     * 少了这个状态，任何一次回填重写都会把关键字静默冻结成数字。
     */
    private String valueKeyword;

    public ConfigEntry(String name, Map<String, String> notes, Class<?> type, T defaultValue,
            String defaultKeyword, Map<String, Keyword<T>> keywords, T min, T max,
            Constraint<T> constraint) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid config entry name: %s".formatted(name));
        }
        this.name = name;
        this.notes = Map.copyOf(notes);
        this.type = type;
        this.defaultValue = defaultValue;
        this.defaultKeyword = defaultKeyword;
        this.keywords = Map.copyOf(keywords);
        this.min = min;
        this.max = max;
        this.constraint = constraint;
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

    /** 默认值是否为关键字。是则 {@link #defaultKeyword()} 给出其名。 */
    public boolean isDefaultKeyword() {
        return defaultKeyword != null;
    }

    /** 默认值命中的关键字名；不是关键字时为 null。 */
    public String defaultKeyword() {
        return defaultKeyword;
    }

    /** 该条目声明的全部关键字。无声明时为空 map。 */
    public Map<String, Keyword<T>> keywords() {
        return keywords;
    }

    /** 下界声明，未声明为 null。 */
    public T min() {
        return min;
    }

    /** 上界声明，未声明为 null。 */
    public T max() {
        return max;
    }

    public Constraint<T> constraint() {
        return constraint;
    }

    public T get() {
        return value;
    }

    public void set(T v) {
        this.value = v;
    }

    public boolean isKeyword() {
        return valueKeyword != null;
    }

    public String keyword() {
        return valueKeyword;
    }

    public void setKeyword(String kwd) {
        this.valueKeyword = kwd;
    }
}
