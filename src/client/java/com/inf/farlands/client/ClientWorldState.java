package com.inf.farlands.client;

import com.inf.farlands.util.maps.AquiferUtil;
import com.inf.farlands.util.maps.BlockUtil;
import com.inf.farlands.util.maps.Common;
import com.inf.farlands.util.maps.SectionUtil;

/**
 * 客户端卸关卡时的进程级状态清理。
 *
 * <p>三张位置侧信道表与 §5 待应用缓存都是进程级静态量，键只到坐标或坐标加维度。同一个客户端进程里
 * 换世界时，上一个世界的条目会命中新世界同坐标的查询：§5 缓存里存的是方块字节，会被直接写进新世界的
 * chunk；而三张位置表若留着旧条目，打包键与新世界的同坐标条目逐字一致，清掉之后才会走位解码兜底，
 * 那时把哈希当坐标就是垃圾，所以清表必须挑在旧世界的打包键没有活持有者的那一刻。
 *
 * <p>落点因此是关卡已经卸下的两处：{@code Minecraft.disconnect} 三参重载的 RETURN，与
 * {@code Minecraft.clearClientLevel} 的 RETURN。两条路径走到那里时集成服务端线程已死、level 已置空、
 * updateLevelInEngines(null) 已跑、player 已空。{@code disconnect} 的四个包装调用者最终都汇入三参那个
 * 重载，所以只需盯着它一处；{@code clearClientLevel} 走的是服务端要求重入配置阶段的路径，不经过
 * disconnect，必须单独盯。
 *
 * <p>幂等，两条路径都会调。
 */
public final class ClientWorldState {

    private ClientWorldState() {
    }

    /** 卸关卡后清掉全部按世界的进程级状态。 */
    public static void clearOnLevelDrop() {
        Common.clearPendingSectionData();
        BlockUtil.clearAll();
        SectionUtil.clearAll();
        AquiferUtil.clearAll();
    }
}
