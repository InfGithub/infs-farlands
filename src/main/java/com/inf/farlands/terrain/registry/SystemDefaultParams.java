package com.inf.farlands.terrain.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在系统类的参数声明字段上。字段必须是 static final SystemParams，读取见
 * {@link SystemParams#declaredBy(Class)}。
 *
 * <p>
 * 用注解而不是字段名约定：字段改名或漏写会静默变成「没有声明」，注解能让这种漏写在扫描时就报出来。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SystemDefaultParams {
}
