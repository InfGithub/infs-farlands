package com.inf.farlands.terrain.registry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.minecraft.resources.Identifier;

/**
 * 进页钩子：创建世界页签里进入某个维度页时，按登记顺序执行登记过的动作。
 *
 * <p>
 * 登记公开，本模组自己的逻辑与外部模组走同一条路。载荷是该页的维度 id，登记顺序即执行顺序。登记都
 * 发生在模组初始化期，触发都在客户端主线程，所以列表用写时复制，读侧不加锁。
 *
 * <p>
 * 触发判据是「人进了某一页」，与「页签对象成了当前页」不是一回事。因此每次进页都触发：创建世界页
 * 刚打开时的那一页，重建世界那条流程里新开的屏幕，以及用户点页签真正换页。界面重建，例如窗口缩放
 * 引起的重建，不算进页，不触发。注册项要自己承受同一页可能被多次触发，见各自的会话边界。
 *
 * <p>
 * 触发点只在有创建世界页的一侧：专用服务器不会触发，登记本身无害。
 */
public final class SystemPageHooks {

    private static final List<Consumer<Identifier>> ENTERED = new CopyOnWriteArrayList<>();

    private SystemPageHooks() {
    }

    /** 登记一个进页动作。 */
    public static void onEnter(Consumer<Identifier> hook) {
        ENTERED.add(hook);
    }

    /** 触发一次进页。由创建世界页签调用，载荷是该页的维度 id。 */
    public static void fireEntered(Identifier dimension) {
        for (Consumer<Identifier> hook : ENTERED) {
            hook.accept(dimension);
        }
    }
}
