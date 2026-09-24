package com.inf.farlands.terrain.registry;

import net.minecraft.resources.Identifier;

/**
 * 系统注册表的键。
 *
 * <p>只接受已构造的 Identifier，不做字符串解析，id 的字符集校验由 Identifier 自己承担。
 * toString 返回纯 id，日志与列表里直接可读。
 */
public record SystemId(Identifier value) {

    @Override
    public String toString() {
        return this.value.toString();
    }
}
