package com.inf.farlands.client.mixin.compat.sodium;

import com.inf.farlands.client.compat.sodium.FarlandsTaskSectionTable;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.minecraft.core.SectionPos;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TaskCollectingTree 的记录侧：每收集一个待建任务，就把「打包后的任务 long -> 该段真实 key」登记进本棵树的表，
 * 生成任务表时把表挂到 DeferredTaskList 上。Sodium 0.9.2 专属构造，升级即碎。
 *
 * <p>打包 long 直接取 {@code pendingTasks} 的最后一个元素，不复刻 {@code getSectionPriority}：那条算式依赖
 * creationTime 与相机，重算容易错位。真实 key 用 {@code SectionPos.asLong} 求，与解码侧、与
 * {@code renderSections} 的键完全同源。
 */
@Pseudo
@Mixin(TaskCollectingTree.class)
public abstract class TaskCollectingTreeMixin {

    @Shadow
    @Final
    private LongArrayList pendingTasks;

    @Unique
    private Long2LongMap farlands$taskSections;

    @Inject(method = "addPendingSection", at = @At("TAIL"))
    private void farlands$recordTaskSection(RenderSection section, int type, boolean inFrustum, CallbackInfo ci) {
        if (this.pendingTasks.isEmpty()) {
            return;
        }
        if (this.farlands$taskSections == null) {
            this.farlands$taskSections = new Long2LongOpenHashMap();
        }
        long packed = this.pendingTasks.getLong(this.pendingTasks.size() - 1);
        this.farlands$taskSections.put(packed,
                SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }

    @Inject(method = "getPendingTaskLists", at = @At("RETURN"))
    private void farlands$attachTaskSections(CallbackInfoReturnable<DeferredTaskList> cir) {
        if (this.farlands$taskSections == null) {
            return;
        }
        DeferredTaskList list = cir.getReturnValue();
        if (list != null) {
            ((FarlandsTaskSectionTable) list).farlands$setTaskSections(this.farlands$taskSections);
        }
    }
}
