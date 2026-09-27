package com.inf.farlands.terrain.registry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个系统声明的参数表，由标注了 {@link SystemDefaultParams} 的静态字段携带。
 *
 * <p>
 * 语义三分，调用方必须分清：
 * EMPTY 表示这个系统不吃参数，界面给 0 个框；
 * null，即没有带注解的字段或字段值为 null，表示没有声明，界面退化成一个自由文本框；
 * 字段形状不对是错误，直接抛，不静默当成没声明。
 */
public record SystemParams(List<SystemParamSpec> entries) {

    public static final SystemParams EMPTY = new SystemParams(List.of());

    public SystemParams {
        entries = List.copyOf(entries);
        List<String> keys = new ArrayList<>(entries.size());
        for (SystemParamSpec spec : entries) {
            if (keys.contains(spec.key())) {
                throw new IllegalArgumentException("Duplicate system param key: " + spec.key());
            }
            keys.add(spec.key());
        }
    }

    public static SystemParams of(SystemParamSpec... specs) {
        return new SystemParams(List.of(specs));
    }

    /**
     * 读该类的参数声明。没有带注解的字段或字段值为 null 时返回 null。带注解的字段多于一个，或字段不是
     * static final SystemParams 时抛：字段扫描顺序没有规范保证，不能取第一个了事。
     */
    public static SystemParams declaredBy(Class<?> systemClass) {
        Field declared = null;
        for (Field field : systemClass.getDeclaredFields()) {
            if (!field.isAnnotationPresent(SystemDefaultParams.class)) {
                continue;
            }
            if (declared != null) {
                throw new IllegalStateException(
                        "farlands: " + systemClass.getName() + " has more than one @SystemDefaultParams field");
            }
            declared = field;
        }
        if (declared == null) {
            return null;
        }
        if (!Modifier.isStatic(declared.getModifiers()) || !Modifier.isFinal(declared.getModifiers())
                || declared.getType() != SystemParams.class) {
            throw new IllegalStateException(
                    "farlands: " + systemClass.getName() + " @SystemDefaultParams field must be static final SystemParams");
        }
        try {
            declared.setAccessible(true);
            return (SystemParams) declared.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("farlands: failed to read param declaration of " + systemClass.getName(), e);
        }
    }
}
