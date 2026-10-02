package com.inf.farlands.terrain.system.common.overworld.Vanilla;

import java.util.concurrent.ThreadLocalRandom;

import com.inf.farlands.terrain.registry.SystemPageHooks;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemsData.Arg;

/**
 * overworld Vanilla 四族共用的 seed 取值源。
 *
 * <p>
 * 四族的 seed 参数都声明同一个空框关键字，指向这里的 {@link #next()}。关键字在
 * {@code SystemSelectionParser.fromEmpty} 处求值，四族各求一次；共用同一个静态量，同一次创建里
 * 四个空框因此拿到同一个随机值，而不是四个互不相同的值。
 *
 * <p>
 * 会话边界是进页：本类把 {@link #reset()} 登记到 {@link SystemPageHooks} 上，进页时重抽，于是同一次
 * 停留里只有一个值。界面与本类之间只经过那一个公开钩子，界面里不出现本类的任何符号。
 *
 * <p>
 * 求值点只有客户端主线程一处，同步只是让这个静态量本身无歧义。
 */
public final class VanillaFamilySeed {

    /** 本次会话共用的随机 seed，未抽过时为 null。 */
    private static volatile Long shared;

    private VanillaFamilySeed() {
    }

    /** 四族共用的 seed 参数声明：空框抽一次，抽到的值在本次会话内固定。 */
    public static SystemParamSpec seedParam() {
        return SystemParamSpec.ofLong("seed")
                .keyword("", () -> Arg.ofLong(next()),
                        "createWorld.tab.infs-farlands.param.seed.random")
                .build();
    }

    /** 把空框会话挂到进页钩子上。由模组登记期调用一次。 */
    public static void registerPageHook() {
        SystemPageHooks.onEnter(dimension -> reset());
    }

    /** 空框取值：首次调用时抽一个并记住，此后同值。 */
    public static long next() {
        Long current = shared;
        if (current != null) {
            return current;
        }
        synchronized (VanillaFamilySeed.class) {
            if (shared == null) {
                shared = ThreadLocalRandom.current().nextLong();
            }
            return shared;
        }
    }

    /** 清掉本次会话的值，下一次空框求值重新抽。只由本类登记的那个进页动作调用。 */
    private static void reset() {
        shared = null;
    }
}
