package com.inf.farlands.client.mixin.gui;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 滚动容器把焦点设给已经持有焦点的同一个子控件时，不再走一遍清焦。
 *
 * <p>AbstractContainerWidget.setFocused 没有同值判断：父层把焦点设给当前已聚焦的子控件时，会先调它的
 * setFocused(false) 再调 setFocused(true)。而容器上的 false 会清空整棵子树的焦点，ContainerEventHandler
 * 的布尔默认实现把 false 转成 setFocused(null)，true 又是空操作，于是子控件内部那一层焦点被抹掉且补不
 * 回来。AbstractContainerEventHandler 与 AbstractSelectionList 各自用同一形式的同值判断绕开了这一点，
 * 这个滚动容器没有。
 *
 * <p>本模组的参数编辑区正是一个自己持焦点的容器，落在这种容器里：第一次点击能编辑，之后每次点击都会把
 * 刚点中的输入框焦点清掉，表现为点得中、打不进字。这里在 HEAD 同值时整体取消，附带不再触发下面那段
 * 键盘滚动，那段滚动以整个参数组的矩形为准，本来就偏粗。
 */
@Mixin(targets = "net.minecraft.client.gui.components.ScrollableLayout$Container")
public class ScrollableLayoutContainerMixin {

    @Inject(
            method = "setFocused(Lnet/minecraft/client/gui/components/events/GuiEventListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void farlands$skipSameFocus(GuiEventListener focused, CallbackInfo ci) {
        if (((ContainerEventHandler) (Object) this).getFocused() == focused) {
            ci.cancel();
        }
    }
}
