package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.client.compat.sodium.FarlandsTaskSectionTable;

import it.unimi.dsi.fastutil.longs.Long2LongMap;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DeferredTaskList 的解码侧：出队时按打包 long 查表，命中就把真实段 key 交出去，未命中保留 Sodium 原解码。
 * Sodium 0.9.2 专属构造，升级即碎。
 *
 * <p>命中时必须先 {@code dequeueLong()} 再返回，保持堆的消费语义；本方法唯一消费点是
 * {@code RenderSectionManager} 的 deferred 派发循环，返回值直接喂 {@code getConsistent(key)}。
 */
@Pseudo
@Mixin(DeferredTaskList.class)
public abstract class DeferredTaskListMixin implements FarlandsTaskSectionTable {

    @Unique
    private Long2LongMap farlands$taskSections;

    @Override
    public void farlands$setTaskSections(Long2LongMap table) {
        this.farlands$taskSections = table;
    }

    @Inject(method = "dequeueNextSectionPos", at = @At("HEAD"), cancellable = true)
    private void farlands$resolveTaskSection(CallbackInfoReturnable<Long> cir) {
        if (this.farlands$taskSections == null || this.farlands$taskSections.isEmpty()) {
            return;
        }
        DeferredTaskList self = (DeferredTaskList) (Object) this;
        long packed = self.firstLong();
        if (this.farlands$taskSections.containsKey(packed)) {
            long resolved = this.farlands$taskSections.get(packed);
            self.dequeueLong();
            cir.setReturnValue(resolved);
        }
    }
}
