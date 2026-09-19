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

/**
 * 配置文件读写。
 *
 * <p>顶层结构：
 * <pre>{@code
 * {
 * "note": { "<lang>": "<text>", ... }, // 多语言注释
 * "lastWriteBackTime": <unixSeconds>, // 最后回写时间戳
 * "settings": {
 * "<entryName>": { "note": { ... }, "value": <literal>, "default": <literal>,
 * "enums": [ ... ] },
 * ...
 * }
 * }
 * }</pre>
 *
 * <p>"enums" 只出现在枚举条目上，列出该枚举当前的全部取值，供手改配置时对照。
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

    private ConfigFile() {
    }

    /**
     * 加载配置文件，若不存在则生成默认；若存在则读值覆盖，缺键补默认回写。
     * 空文件 / 纯空白视为无配置 -> 重新生成默认。
     */
    public static void load(Path file, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries) {
        if (!Files.exists(file)) {
            write(file, fileNotes, entries, System.currentTimeMillis() / 1000L);
            return;
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            // 空文件 / 非法 JSON：视为无配置，重新生成默认。非空但损坏的 JSON 也走这里重置。
            write(file, fileNotes, entries, System.currentTimeMillis() / 1000L);
            return;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config %s: %s".formatted(file, e.getMessage()), e);
        }

        boolean[] dirty = { false };
        JsonObject settings = ensureSettings(root, file, dirty);
        if (dirty[0]) {
            write(file, fileNotes, entries, root.get(LAST_WRITE_BACK_TIME).getAsLong());
            return; // 结构缺失重建后直接落盘，避免重复写
        }

        // 读每个注册项：存在->校验类型并覆盖；缺失->补默认
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
                if (!obj.has(DEFAULT)) {
                    obj.add(DEFAULT, toValueJson(entry.defaultValue(), entry.type()));
                    dirty2[0] = true;
                }
                if (entry.type().isEnum() && !obj.has(ENUMS)) {
                    addEnumsIfEnum(obj, entry.type());
                    dirty2[0] = true;
                }
            } else {
                JsonObject obj = new JsonObject();
                obj.add(NOTE, toNotesJson(entry.notes()));
                obj.add(VALUE, toValueJson(entry.defaultValue(), entry.type()));
                obj.add(DEFAULT, toValueJson(entry.defaultValue(), entry.type()));
                addEnumsIfEnum(obj, entry.type());
                settings.add(name, obj);
                dirty2[0] = true;
            }
        }
        if (dirty2[0]) {
            write(file, fileNotes, entries, root.get(LAST_WRITE_BACK_TIME).getAsLong());
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

    private static void write(Path file, Map<String, String> fileNotes,
            Map<String, ConfigEntry<?>> entries, long lastWriteBackTime) {
        JsonObject root = new JsonObject();
        root.add(NOTE, toNotesJson(fileNotes));
        root.addProperty(LAST_WRITE_BACK_TIME, lastWriteBackTime);
        JsonObject settings = new JsonObject();
        for (Map.Entry<String, ConfigEntry<?>> e : entries.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.add(NOTE, toNotesJson(e.getValue().notes()));
            // 这里写当前值而不是默认值：缺 default 或 enums 触发的回填重写，不能把用户改过的值冲掉
            obj.add(VALUE, toValueJson(e.getValue().get(), e.getValue().type()));
            obj.add(DEFAULT, toValueJson(e.getValue().defaultValue(), e.getValue().type()));
            addEnumsIfEnum(obj, e.getValue().type());
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

    // notes 序列化

    private static JsonObject toNotesJson(Map<String, String> notes) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : notes.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }

    // enums 序列化

    /** 枚举条目补上 enums 列表，即该枚举当前的全部取值；非枚举类型不写这个键。 */
    private static void addEnumsIfEnum(JsonObject obj, Class<?> type) {
        if (!type.isEnum()) {
            return;
        }
        JsonArray arr = new JsonArray();
        for (Object constant : type.getEnumConstants()) {
            arr.add(((Enum<?>) constant).name());
        }
        obj.add(ENUMS, arr);
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

    // value 反序列化：类型分派 + 严格校验

    private static Object readValue(JsonElement el, ConfigEntry<?> entry, Path file) {
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

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setValue(ConfigEntry entry, Object v) {
        entry.set(v);
    }
}
