package com.inf.farlands.client.compat.sodium;

import it.unimi.dsi.fastutil.longs.Long2LongMap;

/**
 * DeferredTaskList 上的任务段表注入点：由 TaskCollectingTree 在生成任务表时挂上，解码时按打包值还原真实段。
 *
 * <p>Sodium 0.9.2 的任务表把段坐标压进 10 位，Y 以 {@code TaskCollectingTree.SECTION_Y_MIN = -128} 为基准，
 * 我们的段号远超这个表示范围：chunkY 写进去时按 1024 取模，解码再加回基准，得到的是同余类里的另一个值
 * （实测 134217726 解成 -2 附近），于是 deferred 那条路的构建任务永远落到别的段上。important 那条路不走
 * 这个打包，所以手动放置能建、服务端更新标脏的不能建。
 *
 * <p>一次 cull 收集的段在竖直方向相差远小于 1024（cull 的 search distance 只有 256 格），因此打包值在该
 * 集合内是单射，用一张「打包值 -> 真实段 key」的表即可无损还原，不需要消歧规则。表与那次 cull 同生共死。
 *
 * <p>注入点由 mixin 实现，普通接口不登记进 mixins.json。Sodium 缺席时整层不加载。
 */
public interface FarlandsTaskSectionTable {

    void farlands$setTaskSections(Long2LongMap table);
}
