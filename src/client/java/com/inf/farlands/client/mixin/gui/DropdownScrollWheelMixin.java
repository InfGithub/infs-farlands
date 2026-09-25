package com.inf.farlands.client.mixin.gui;

import com.inf.farlands.client.gui.demo.DropdownSelect;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 滚动区收到滚轮时，先把机会让给指针所在的展开下拉列表。
 *
 * <p>
 * 26.1.2 的 {@code AbstractScrollArea.mouseScrolled} 不看坐标就滚自己并返回 true。而 {@code Screen} 的
 * 孩子里只有 {@code ScrollableLayout} 的 {@code Container}，下拉 {@code DropdownSelect} 只在
 * {@code Container} 自己的 child 列表里，屏幕那一层取不到它们。于是指针落在展开的列表上滚轮时，事件被
 * {@code Container} 自己吃掉，列表不动、页面滚动。
 *
 * <p>
 * 点击没有这个问题：{@code AbstractContainerWidget.mouseClicked} 会先走
 * {@code ContainerEventHandler.super.mouseClicked}，把事件交给 {@code getChildAt} 取到的 child。
 * 滚轮缺的正是这一步转发，这里补上。
 *
 * <p>
 * 判据是 child 里有没有 {@code DropdownSelect} 且 {@link DropdownSelect#isOverOpenList} 为真：只有指针
 * 确实落在某个展开列表内才截走。没有下拉的滚动区扫描不到候选，行为与 vanilla 逐字相同。委托只在
 * {@code isOverOpenList} 为真时发生，不会把滚轮交给别的控件。
 *
 * <p>
 * 混入体零字段。目标方法声明在 {@code AbstractScrollArea} 上，{@code ScrollableLayout$Container} 与
 * {@code AbstractContainerWidget} 都没有它。
 */
@Mixin(AbstractScrollArea.class)
public class DropdownScrollWheelMixin {

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void farlands$openDropdownConsumesWheel(double mouseX, double mouseY, double scrollX,
            double scrollY, CallbackInfoReturnable<Boolean> cir) {
        for (GuiEventListener child : ((ContainerEventHandler) this).children()) {
            if (child instanceof DropdownSelect dropdown && dropdown.isOverOpenList(mouseX, mouseY)) {
                dropdown.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
