package com.inf.farlands.util.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 配置文件读写。
 *
 * <p>顶层结构：
 * <pre>{@code
 * {
 * "note": { "<source>": "<text>", ... }, // 注释来源 -> 注释
 * "lastWriteBackTime": <unixSeconds>, // 最后回写时间戳
 * "settings": {
 * "<entryName>": { "note": { ... }, "value": <literal|"auto">, "default": <literal|"auto">,
 * "range": { "min": <lo>, "max": <hi> }, "enums": [ ... ] },
 * ...
 * }
 * }
 * }</pre>
 *
 * <p>{@code "enums"} 只出现在枚举条目上，列出该枚举当前的全部取值；{@code "range"} 只出现在
 * 声明了范围的条目上，列出该范围。两者都是<b>信息字段</b>，供手改配置时对照，读盘期不参与校验。
 *
 * <p>{@code "value"} 与 {@code "default"} 除字面量外还接受保留字面量 {@code "auto"}；写 {@code "auto"}
 * 时由该条目的 auto 取值器在读盘期求值一次。没有取值器却写 {@code "auto"} 会抛异常。
 *
 * <p>回写一致性：`load` 会把下列漂移都标脏并触发一次全量重写——{@code default} 与代码声明不符、
 * {@code enums}/{@code range} 与代码声明不符、文件里残留了代码侧已不存在的 {@code enums}/{@code range}
 * 键、以及 {@code note} 里由 {@code comment(...)} 声明的来源键与声明不符。{@code note} 的额外键
 * （代码没声明的来源）不参与比较，也不会被删：重写时是覆盖式写入声明键。
 */
public final class ConfigFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String NOTE = "note";
    private static final String LAST_WRITE_BACK_TIME = "lastWriteBackTime";
    private static final String SETTINGS = "settings";
    private static final String VALUE = "value";
    private static final String DEFAULT = "default";
    private static final String ENUMS = "enums";
    private static final String RANGE = "range";

    private ConfigFile() {
    }

    /**
     * 加载配置文件，若不存在则生成默认；若存在则读值覆盖，漂移或缺键则回写。
     * 空文件 / 纯空白视为无配置 -> 重新生成默认。
     */
    public static void load(Path file, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries) {
        if (!Files.exists(file)) {
            applyDefaults(entries);
            write(file, null, fileNotes, entries, System.currentTimeMillis() / 1000L);
            return;
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            // 空文件 / 非法 JSON：视为无配置，重新生成默认。非空但损坏的 JSON 也走这里重置。
            applyDefaults(entries);
            write(file, null, fileNotes, entries, System.currentTimeMillis() / 1000L);
            return;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config %s: %s".formatted(file, e.getMessage()), e);
        }

        boolean[] dirty = { false };
        JsonObject settings = ensureSettings(root, file, dirty);
        if (dirty[0]) {
            write(file, root, fileNotes, entries, root.get(LAST_WRITE_BACK_TIME).getAsLong());
            return; // 结构缺失重建后直接落盘，避免重复写
        }

        // 读每个注册项：存在->校验类型与范围并覆盖，同时对齐 default/enums/range/note；缺失->补默认
        boolean[] dirty2 = { false };
        for (Map.Entry<String, ConfigEntry<?>> e : entries.entrySet()) {
            String name = e.getKey();
            ConfigEntry<?> entry = e.getValue();
            if (settings.has(name)) {
                JsonObject obj = settings.getAsJsonObject(name);
                if (!obj.has(VALUE)) {
                    throw new RuntimeException("Config entry '%s' missing 'value' in %s".formatted(name, file));
                }
                Object v = readValue(obj.get(VALUE), entry, file);
                setValue(entry, v);
                if (align(obj, DEFAULT, defaultValueJson(entry))) {
                    dirty2[0] = true;
                }
                if (entry.type().isEnum()) {
                    if (align(obj, ENUMS, enumArray(entry.type()))) {
                        dirty2[0] = true;
                    }
                } else if (obj.has(ENUMS)) {
                    obj.remove(ENUMS); // 代码侧已不是枚举，残留键要清掉
                    dirty2[0] = true;
                }
                if (entry.range() != null) {
                    if (align(obj, RANGE, rangeObject(entry.range()))) {
                        dirty2[0] = true;
                    }
                } else if (obj.has(RANGE)) {
                    obj.remove(RANGE); // 代码侧已无范围，残留键要清掉
                    dirty2[0] = true;
                }
                if (noteDrift(obj, entry)) {
                    mergeNotesInto(obj, entry.notes());
                    dirty2[0] = true;
                }
            } else {
                JsonObject obj = new JsonObject();
                obj.add(NOTE, toNotesJson(entry.notes()));
                // 新条目的值就是它的默认值；默认即 auto 时在这里求值一次
                applyDefault(entry);
                obj.add(VALUE, valueJson(entry));
                obj.add(DEFAULT, defaultValueJson(entry));
                if (entry.type().isEnum()) {
                    obj.add(ENUMS, enumArray(entry.type()));
                }
                if (entry.range() != null) {
                    obj.add(RANGE, rangeObject(entry.range()));
                }
                settings.add(name, obj);
                dirty2[0] = true;
            }
        }
        if (dirty2[0]) {
            write(file, root, fileNotes, entries, root.get(LAST_WRITE_BACK_TIME).getAsLong());
        }
    }

    /** settings/lastWriteBackTime 键存在校验；缺失则补并标记。 */
    private static JsonObject ensureSettings(JsonObject root, Path file, boolean[] dirty) {
        if (!root.has(LAST_WRITE_BACK_TIME)) {
            root.addProperty(LAST_WRITE_BACK_TIME, System.currentTimeMillis() / 1000L);
            dirty[0] = true;
        }
        if (!root.has(SETTINGS) || !root.get(SETTINGS).isJsonObject()) {
            root.add(SETTINGS, new JsonObject());
            dirty[0] = true;
        }
        return root.getAsJsonObject(SETTINGS);
    }

    // 写文件

    /**
     * 全量重写。
     *
     * <p>{@code existingRoot} 是已解析的旧文件（文件不存在或损坏时传 null），只用于让 {@code note}
     * 里的额外来源键活下来：写入是覆盖式的，声明键被更新，其余键原样保留。
     */
    private static void write(Path file, JsonObject existingRoot, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries, long lastWriteBackTime) {
        JsonObject root = new JsonObject();
        root.add(NOTE, mergeNotes(existingRoot == null ? null : existingRoot.get(NOTE), fileNotes));
        root.addProperty(LAST_WRITE_BACK_TIME, lastWriteBackTime);
        JsonObject oldSettings = existingRoot != null && existingRoot.get(SETTINGS) != null
                && existingRoot.get(SETTINGS).isJsonObject() ? existingRoot.getAsJsonObject(SETTINGS) : null;
        JsonObject settings = new JsonObject();
        for (Map.Entry<String, ConfigEntry<?>> e : entries.entrySet()) {
            ConfigEntry<?> entry = e.getValue();
            JsonObject oldObj = oldSettings != null && oldSettings.get(e.getKey()) != null
                    && oldSettings.get(e.getKey()).isJsonObject()
                            ? oldSettings.getAsJsonObject(e.getKey())
                            : null;
            JsonObject obj = new JsonObject();
            obj.add(NOTE, mergeNotes(oldObj == null ? null : oldObj.get(NOTE), entry.notes()));
            // 这里写当前值而不是默认值：触发的回填重写不能把用户改过的值冲掉；
            // 但当前值读自 auto 时必须写回 "auto"，否则第一次重写就把 auto 冻结成数字
            obj.add(VALUE, valueJson(entry));
            obj.add(DEFAULT, defaultValueJson(entry));
            if (entry.type().isEnum()) {
                obj.add(ENUMS, enumArray(entry.type()));
            }
            if (entry.range() != null) {
                obj.add(RANGE, rangeObject(entry.range()));
            }
            settings.add(e.getKey(), obj);
        }
        root.add(SETTINGS, settings);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write config %s: %s".formatted(file, ex.getMessage()), ex);
        }
    }

    // 值序列化

    /** 当前值的 JSON：读自 auto 则写保留字面量，否则写字面值。 */
    private static JsonElement valueJson(ConfigEntry<?> entry) {
        if (entry.isAuto()) {
            return new JsonPrimitive(Config.AUTO);
        }
        return toValueJson(entry.get(), entry.type());
    }

    /** 默认值的 JSON：默认即 auto 则写保留字面量，否则写字面值。 */
    private static JsonElement defaultValueJson(ConfigEntry<?> entry) {
        if (entry.isDefaultAuto()) {
            return new JsonPrimitive(Config.AUTO);
        }
        return toValueJson(entry.defaultValue(), entry.type());
    }

    // notes 序列化

    private static JsonObject toNotesJson(Map<String, String> notes) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : notes.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    /** 覆盖式合并：写入声明键，保留 {@code existing} 里其余键。不改动传入的树。 */
    private static JsonObject mergeNotes(JsonElement existing, Map<String, String> declared) {
        JsonObject note = existing != null && existing.isJsonObject()
                ? existing.getAsJsonObject().deepCopy()
                : new JsonObject();
        declared.forEach(note::addProperty);
        return note;
    }

    private static void mergeNotesInto(JsonObject entryObj, Map<String, String> declared) {
        entryObj.add(NOTE, mergeNotes(entryObj.get(NOTE), declared));
    }

    /** 声明过的来源键是否与文件里的不符。额外键不参与比较。 */
    private static boolean noteDrift(JsonObject entryObj, ConfigEntry<?> entry) {
        JsonElement el = entryObj.get(NOTE);
        JsonObject have = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        for (Map.Entry<String, String> e : entry.notes().entrySet()) {
            JsonElement v = have == null ? null : have.get(e.getKey());
            if (v == null || !v.isJsonPrimitive() || !v.getAsString().equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    // enums / range 序列化

    private static JsonArray enumArray(Class<?> type) {
        JsonArray arr = new JsonArray();
        for (Object constant : type.getEnumConstants()) {
            arr.add(((Enum<?>) constant).name());
        }
        return arr;
    }

    private static JsonObject rangeObject(ConfigEntry.Range range) {
        JsonObject o = new JsonObject();
        o.add("min", new JsonPrimitive(range.min()));
        o.add("max", new JsonPrimitive(range.max()));
        return o;
    }

    /** 把 obj 的 key 对齐到 want：缺失或不符都替换，返回是否发生了替换。 */
    private static boolean align(JsonObject obj, String key, JsonElement want) {
        JsonElement have = obj.get(key);
        if (have != null && have.equals(want)) {
            return false;
        }
        obj.add(key, want);
        return true;
    }

    // value 序列化：类型分派

    private static JsonElement toValueJson(Object value, Class<?> type) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (type == int.class || type == Integer.class) {
            return new JsonPrimitive((Integer) value);
        }
        if (type == long.class || type == Long.class) {
            return new JsonPrimitive((Long) value);
        }
        if (type == short.class || type == Short.class) {
            return new JsonPrimitive((Short) value);
        }
        if (type == byte.class || type == Byte.class) {
            return new JsonPrimitive((Byte) value);
        }
        if (type == float.class || type == Float.class) {
            return new JsonPrimitive((Float) value);
        }
        if (type == double.class || type == Double.class) {
            return new JsonPrimitive((Double) value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return new JsonPrimitive((Boolean) value);
        }
        if (type == String.class) {
            return new JsonPrimitive((String) value);
        }
        if (type.isEnum()) {
            return new JsonPrimitive(((Enum<?>) value).name());
        }
        throw new IllegalArgumentException("Unsupported config type: %s".formatted(type));
    }

    // value 反序列化：auto 保留字面量 -> 取值器；否则类型分派 + 严格校验 + 范围约束

    private static Object readValue(JsonElement el, ConfigEntry<?> entry, Path file) {
        if (isAutoToken(el)) {
            Supplier<?> supplier = entry.autoValue();
            if (supplier == null) {
                throw new RuntimeException("Config %s entry '%s': value \"%s\" but no auto supplier is declared"
                        .formatted(file, entry.name(), Config.AUTO));
            }
            entry.setAuto(true);
            return supplier.get(); // auto 派生结果不过范围约束：它不是用户输入
        }
        entry.setAuto(false);
        Object v = readTyped(el, entry, file);
        checkConstraint(entry, v, file);
        return v;
    }

    /** 保留字面量严格匹配：{@code "AUTO"}、{@code "Auto"} 都不是它。 */
    private static boolean isAutoToken(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                && Config.AUTO.equals(el.getAsString());
    }

    private static Object readTyped(JsonElement el, ConfigEntry<?> entry, Path file) {
        Class<?> type = entry.type();
        if (type == int.class || type == Integer.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of int range"
                        .formatted(file, entry.name(), v));
            }
            return (int) v;
        }
        if (type == long.class || type == Long.class) {
            // Gson getAsLong 对超 long 范围会饱和，用 BigDecimal 精确校验
            return requireIntegralExact(el, file, Long.MIN_VALUE, Long.MAX_VALUE, entry.name());
        }
        if (type == short.class || type == Short.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of short range"
                        .formatted(file, entry.name(), v));
            }
            return (short) v;
        }
        if (type == byte.class || type == Byte.class) {
            long v = requireNumber(el, file).longValue();
            if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of byte range"
                        .formatted(file, entry.name(), v));
            }
            return (byte) v;
        }
        if (type == float.class || type == Float.class) {
            double d = requireNumber(el, file).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)
                    || d < -Float.MAX_VALUE || d > Float.MAX_VALUE) {
                throw new RuntimeException("Config %s entry '%s': value %s out of float range"
                        .formatted(file, entry.name(), d));
            }
            return (float) d;
        }
        if (type == double.class || type == Double.class) {
            double d = requireNumber(el, file).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new RuntimeException("Config %s entry '%s': invalid double value %s"
                        .formatted(file, entry.name(), d));
            }
            return d;
        }
        if (type == boolean.class || type == Boolean.class) {
            return requirePrimitive(el, file).getAsBoolean();
        }
        if (type == String.class) {
            return requirePrimitive(el, file).getAsString();
        }
        if (type.isEnum()) {
            String s = requirePrimitive(el, file).getAsString();
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object v = Enum.valueOf((Class<? extends Enum>) type, s);
            return v; // 未知枚举名 -> IllegalArgumentException 向上抛
        }
        throw new IllegalArgumentException("Unsupported config type: %s".formatted(type));
    }

    /** 范围约束。异构注册表只能走 raw 桥接，与 {@link #setValue} 同形。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void checkConstraint(ConfigEntry entry, Object v, Path file) {
        ConfigEntry.Constraint c = entry.constraint();
        if (c == null) {
            return;
        }
        try {
            c.check(v);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Config %s entry '%s': %s".formatted(file, entry.name(), e.getMessage()), e);
        }
    }

    /** 读 Number，非数值抛异常。 */
    private static Number requireNumber(JsonElement el, Path file) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new RuntimeException("Config %s: expected number, got %s".formatted(file, el));
        }
        return el.getAsJsonPrimitive().getAsNumber();
    }

    /** 整型精确读取：用 BigDecimal 校验不丢精度。 */
    private static long requireIntegralExact(JsonElement el, Path file,
            long min, long max, String name) {
        Number n = requireNumber(el, file);
        java.math.BigDecimal bd = new java.math.BigDecimal(n.toString());
        try {
            long v = bd.longValueExact(); // 非整数值或超范围抛 ArithmeticException
            if (v < min || v > max) {
                throw new ArithmeticException();
            }
            return v;
        } catch (ArithmeticException e) {
            throw new RuntimeException("Config %s entry '%s': value %s not a valid integer in [%s, %s]"
                    .formatted(file, name, n, min, max));
        }
    }

    private static JsonPrimitive requirePrimitive(JsonElement el, Path file) {
        if (el == null || !el.isJsonPrimitive()) {
            throw new RuntimeException("Config %s: expected primitive value, got %s".formatted(file, el));
        }
        return el.getAsJsonPrimitive();
    }

    // 泛型桥接：写回 entry.value

    /**
     * 生成与重置路径：把默认值落进每个条目。必须走这一步——auto 默认值的条目在构造时
     * {@code value} 是 null，直接落盘会写出没有 {@code value} 键的文件，而消费方读到的也是 null。
     */
    private static void applyDefaults(Map<String, ConfigEntry<?>> entries) {
        for (ConfigEntry<?> entry : entries.values()) {
            applyDefault(entry);
        }
    }

    /** 无 auto 默认时置为字面默认值；有则求值一次并把来源标成 auto。auto 结果不过范围约束。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void applyDefault(ConfigEntry entry) {
        if (entry.isDefaultAuto()) {
            entry.setAuto(true);
            entry.set(entry.autoValue().get());
        } else {
            entry.setAuto(false);
            entry.set(entry.defaultValue());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setValue(ConfigEntry entry, Object v) {
        entry.set(v);
    }
}
