package com.inf.farlands.util.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.inf.farlands.InfsFarlands;

/**
 * 配置入口：静态持有全部配置项，逐项用 builder 声明。声明形状：
 *
 * <pre>{@code
 * public static final ConfigEntry<Integer> MAX_CAP_ITER = Config.setInt("maxCapIter")
 *         .comment("en_us", "Iteration/search cap for extreme-Y loops.")
 *         .comment("zh_cn", "极端 Y 循环的迭代/搜索上限。")
 *         .range(1, 65536)
 *         .Default(512)
 *         .build();
 * }</pre>
 *
 * <p>值的来源在 value/default 位置上二选一：显式字面量，或保留字面量 {@link #AUTO}。
 * 写 {@code "auto"} 时由 {@link Builder#auto(Supplier)} 声明的取值器在<b>读盘期求值一次</b>。
 * 没声明取值器却写 {@code "auto"} 会在读盘期响亮失败。严格匹配：{@code "AUTO"}、{@code "Auto"} 都不是它。
 * 保留字面量的代价是：声明了取值器的 String 项，字面值 {@code "auto"} 不能再用作值。
 *
 * <p>{@link Builder#Default(String)} 的形参只接受 {@code "auto"}，其余字符串交给
 * {@link Builder#literalDefault(String)}；默认实现直接抛，String 项的 builder 覆写它接受字面量。
 * 基类那个方法标 {@code final}，所以 String 项覆写的是钩子而不是它本身，不会出现同签名遮蔽。
 * 方法名首字母必须大写：{@code default} 是 Java 关键字，不能做方法名。
 *
 * <p>范围（{@code range}）只作用于显式声明的值，见 {@link ConfigEntry.Constraint}。
 *
 * <p>一次性语义：{@link #init()} 是唯一的读盘点，读一次、解析一次、约束一次。
 * 本系统<b>不支持热重载</b>，而且是结构性的：一批配置项在类加载期就被内联进字节码
 * （{@code mixin/expand/xz/border/*} 的 {@code @ModifyConstant}），另一批只在构造时读一次
 * （线程池规模、{@code ViewArea.sections} 的尺寸、{@code LinkedHashMap} 的初始容量）。
 * 重载只会产出「一部分生效、一部分永远不生效」的状态，而那条分界线既不在配置文件里也不在日志里。
 */
public final class Config {

    /** value/default 位置上的保留字面量。 */
    public static final String AUTO = "auto";

    private static final Map<String, ConfigEntry<?>> ENTRIES = new LinkedHashMap<>();

    /** 幂等标志：init() 只读一次文件；多个配置类 static 块重复调用无害。 */
    private static boolean initialized;

    private Config() {
    }

    // ---- 工厂：9 种值类型 ----

    public static IntBuilder setInt(String name) {
        return new IntBuilder(name);
    }

    public static LongBuilder setLong(String name) {
        return new LongBuilder(name);
    }

    public static ShortBuilder setShort(String name) {
        return new ShortBuilder(name);
    }

    public static ByteBuilder setByte(String name) {
        return new ByteBuilder(name);
    }

    public static FloatBuilder setFloat(String name) {
        return new FloatBuilder(name);
    }

    public static DoubleBuilder setDouble(String name) {
        return new DoubleBuilder(name);
    }

    public static BoolBuilder setBoolean(String name) {
        return new BoolBuilder(name);
    }

    /** 类名不叫 StringBuilder：那会遮蔽 {@link java.lang.StringBuilder}。 */
    public static StringsBuilder setString(String name) {
        return new StringsBuilder(name);
    }

    public static <T extends Enum<T>> EnumBuilder<T> setEnum(String name, Class<T> type) {
        return new EnumBuilder<>(name, type);
    }

    // ---- builder ----

    public abstract static class Builder<T, B extends Builder<T, B>> {

        protected final String name;
        protected final Map<String, String> notes = new LinkedHashMap<>();
        protected T defaultValue;
        protected boolean defaultAuto;
        protected ConfigEntry.Range range;
        protected ConfigEntry.Constraint<T> constraint;
        protected Supplier<T> autoValue;

        Builder(String name) {
            if (!ConfigEntry.isValidName(name)) {
                throw new IllegalArgumentException("Invalid config entry name: %s".formatted(name));
            }
            this.name = name;
        }

        public abstract B self();

        protected abstract Class<?> type();

        public B comment(String source, String text) {
            notes.put(source, text);
            return self();
        }

        /** 声明 {@code "auto"} 的取值器。求值发生在本项读盘时，只求一次。 */
        public B auto(Supplier<T> supplier) {
            this.autoValue = supplier;
            return self();
        }

        /**
         * 声明默认值。{@code "auto"} 表示默认即 auto（须已声明取值器）；其余字符串交给
         * {@link #literalDefault(String)}。
         */
        public final B Default(String token) {
            if (AUTO.equals(token)) {
                if (autoValue == null) {
                    throw new IllegalStateException(
                            "Config entry '%s': Default(\"auto\") 但未声明 auto 取值器".formatted(name));
                }
                defaultValue = null;
                defaultAuto = true;
                return self();
            }
            return literalDefault(token);
        }

        /** 非 String 类型的默认实现：不接受任意字符串。String 项覆写它接受字面量。 */
        protected B literalDefault(String value) {
            throw new IllegalArgumentException(
                    "Config entry '%s': Default(String) 只接受 \"auto\"，收到 %s".formatted(name, value));
        }

        public ConfigEntry<T> build() {
            if (ENTRIES.containsKey(name)) {
                throw new IllegalStateException("Duplicate config entry: %s".formatted(name));
            }
            if (defaultValue == null && !defaultAuto) {
                throw new IllegalStateException("Config entry '%s': 缺少 Default(...)".formatted(name));
            }
            if (!defaultAuto && constraint != null) {
                // 默认值自身也要合法，否则一份新生成的配置就是坏的
                constraint.check(defaultValue);
            }
            ConfigEntry<T> e = new ConfigEntry<>(name, notes, type(), defaultValue, range, constraint,
                    autoValue, defaultAuto);
            ENTRIES.put(name, e);
            return e;
        }
    }

    /**
     * 数值类型的公共基类。{@code range} 只在这里出现——非数值类型没有范围，也就没有这个方法。
     * 用 {@code T extends Number & Comparable<T>} 而不是 {@code Comparable<?>} 字段：
     * 后者在共享基类里比较两个通配符参数会撞 wildcard capture。
     */
    public abstract static class NumericBuilder<T extends Number & Comparable<T>, B extends NumericBuilder<T, B>>
            extends Builder<T, B> {

        NumericBuilder(String name) {
            super(name);
        }

        public B range(final T min, final T max) {
            if (min.compareTo(max) > 0) {
                throw new IllegalArgumentException(
                        "Config entry '%s': range min > max (%s > %s)".formatted(name, min, max));
            }
            this.range = new ConfigEntry.Range(min, max);
            this.constraint = value -> {
                if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                    throw new IllegalArgumentException("value %s out of range [%s, %s]".formatted(value, min, max));
                }
            };
            return self();
        }
    }

    public static final class IntBuilder extends NumericBuilder<Integer, IntBuilder> {
        IntBuilder(String name) {
            super(name);
        }

        @Override
        public IntBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return int.class;
        }

        public IntBuilder Default(int value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class LongBuilder extends NumericBuilder<Long, LongBuilder> {
        LongBuilder(String name) {
            super(name);
        }

        @Override
        public LongBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return long.class;
        }

        public LongBuilder Default(long value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class ShortBuilder extends NumericBuilder<Short, ShortBuilder> {
        ShortBuilder(String name) {
            super(name);
        }

        @Override
        public ShortBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return short.class;
        }

        public ShortBuilder Default(short value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class ByteBuilder extends NumericBuilder<Byte, ByteBuilder> {
        ByteBuilder(String name) {
            super(name);
        }

        @Override
        public ByteBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return byte.class;
        }

        public ByteBuilder Default(byte value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class FloatBuilder extends NumericBuilder<Float, FloatBuilder> {
        FloatBuilder(String name) {
            super(name);
        }

        @Override
        public FloatBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return float.class;
        }

        public FloatBuilder Default(float value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class DoubleBuilder extends NumericBuilder<Double, DoubleBuilder> {
        DoubleBuilder(String name) {
            super(name);
        }

        @Override
        public DoubleBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return double.class;
        }

        public DoubleBuilder Default(double value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class BoolBuilder extends Builder<Boolean, BoolBuilder> {
        BoolBuilder(String name) {
            super(name);
        }

        @Override
        public BoolBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return boolean.class;
        }

        public BoolBuilder Default(boolean value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class StringsBuilder extends Builder<String, StringsBuilder> {
        StringsBuilder(String name) {
            super(name);
        }

        @Override
        public StringsBuilder self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return String.class;
        }

        /** String 项：非 {@code "auto"} 的字符串就是字面默认值。 */
        @Override
        protected StringsBuilder literalDefault(String value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    public static final class EnumBuilder<T extends Enum<T>> extends Builder<T, EnumBuilder<T>> {

        private final Class<T> enumType;

        EnumBuilder(String name, Class<T> type) {
            super(name);
            this.enumType = type;
        }

        @Override
        public EnumBuilder<T> self() {
            return this;
        }

        @Override
        protected Class<?> type() {
            return enumType;
        }

        public EnumBuilder<T> Default(T value) {
            defaultValue = value;
            defaultAuto = false;
            return this;
        }
    }

    // ---- 读盘 ----

    /**
     * 读文件。幂等：首次调用读磁盘并填充 ConfigEntry，之后直接返回。
     *
     * <p>唯一调用点是 {@code FarlandsConfig} 的静态块尾部（该类的初始化由首个引用它的类触发）。
     * 该方法之后不再有任何重新读盘的入口——热重载是结构性不可行的，见类注释。
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
