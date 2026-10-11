package com.inf.farlands.serialize;

/**
 * 待建方块实体标签的宿主接口，由 ChunkAccessMixin 注入到 ChunkAccess。
 *
 * <p>
 * 标签本身由 vanilla 的 pendingBlockEntities 承载，本接口只把「某段的标签该晋升了」这件事暴露给读回侧，
 * 不新增存储。方块在 fsa 里，载入瞬间没有方块态可建实例，所以晋升只能发生在该段被读回灌好之后。
 */
public interface PendingBlockEntities {

    /** 把 sectionY 这一段已就位的待建标签逐个促其晋升；段未就位时消费门会拒绝，调用无害。 */
    void promotePendingBlockEntities(int sectionY);
}
