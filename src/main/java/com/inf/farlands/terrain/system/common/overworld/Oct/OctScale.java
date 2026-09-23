package com.inf.farlands.terrain.system.common.overworld.Oct;

/**
 * 三轴缩放。语义与 Hex/Weierstrass 的 ScaledPointInput 一致：输入坐标除以 scale，scale 大于 1 特征变大。
 */
public record OctScale(double x, double y, double z) {
}
