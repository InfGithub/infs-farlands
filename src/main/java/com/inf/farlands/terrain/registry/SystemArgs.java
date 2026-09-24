package com.inf.farlands.terrain.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统构造参数的键值视图。
 *
 * <p>键是裸字符串，按类型存取，类型集与配置系统一致。取值严格同类型，不做数值提升：存 double 的
 * 键只能用 getDouble 取。
 *
 * <p>每次实例化各用一份，由门面在实例化完成后冻结。所有失败都硬抛，不做默认值兜底：缺失键抛
 * IllegalArgumentException，类型不符抛 IllegalStateException，写入 null 抛
 * NullPointerException，冻结后写入抛 IllegalStateException。
 *
 * <p>唯一写入口是按类型的 setter。asMap 返回只读视图，绕过不了 setter，也绕过不了冻结。
 */
public final class SystemArgs {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean frozen;

    /** 键到值的只读视图。是视图不是快照，冻结前填充的变化对它可见。 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(this.values);
    }

    public boolean has(String key) {
        return this.values.containsKey(key);
    }

    /** 冻结后 set 即抛。由门面在实例化完成后调用。 */
    public void freeze() {
        this.frozen = true;
    }

    public void setInt(String key, int value) {
        put(key, value);
    }

    public void setLong(String key, long value) {
        put(key, value);
    }

    public void setShort(String key, short value) {
        put(key, value);
    }

    public void setByte(String key, byte value) {
        put(key, value);
    }

    public void setFloat(String key, float value) {
        put(key, value);
    }

    public void setDouble(String key, double value) {
        put(key, value);
    }

    public void setBoolean(String key, boolean value) {
        put(key, value);
    }

    public void setString(String key, String value) {
        put(key, value);
    }

    public <E extends Enum<E>> void setEnum(String key, E value) {
        put(key, value);
    }

    public int getInt(String key) {
        return (Integer) get(key, Integer.class);
    }

    public long getLong(String key) {
        return (Long) get(key, Long.class);
    }

    public short getShort(String key) {
        return (Short) get(key, Short.class);
    }

    public byte getByte(String key) {
        return (Byte) get(key, Byte.class);
    }

    public float getFloat(String key) {
        return (Float) get(key, Float.class);
    }

    public double getDouble(String key) {
        return (Double) get(key, Double.class);
    }

    public boolean getBoolean(String key) {
        return (Boolean) get(key, Boolean.class);
    }

    public String getString(String key) {
        return (String) get(key, String.class);
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type) {
        return type.cast(get(key, type));
    }

    private void put(String key, Object value) {
        if (this.frozen) {
            throw new IllegalStateException("SystemArgs is frozen: " + key);
        }
        if (value == null) {
            throw new NullPointerException("System arg is null: " + key);
        }
        Object previous = this.values.get(key);
        if (previous != null && previous.getClass() != value.getClass()) {
            throw new IllegalStateException("System arg '%s' already holds %s".formatted(key,
                    previous.getClass().getSimpleName()));
        }
        this.values.put(key, value);
    }

    private Object get(String key, Class<?> type) {
        Object value = this.values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing system arg: " + key);
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("System arg '%s' is %s, not %s".formatted(key,
                    value.getClass().getSimpleName(), type.getSimpleName()));
        }
        return value;
    }
}
