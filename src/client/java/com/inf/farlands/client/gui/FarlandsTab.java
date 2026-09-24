package com.inf.farlands.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;

/**
 * 创建世界页的附加页签，只放一个无作用按钮。
 *
 * <p>
 * 继承 GridLayoutTab 即可，页签控件的注册与移除由 TabManager 负责：切到本页时把 layout 内的
 * 控件加进屏幕，切走时全部移除。所以本类不接触 Screen，也不需要拿到 CreateWorldScreen 实例。
 */
public class FarlandsTab extends GridLayoutTab {

    private static final int BUTTON_WIDTH = 210;

    public FarlandsTab() {
        super(Component.translatable("createWorld.tab.infs-farlands.title"));
        GridLayout.RowHelper helper = this.layout.rowSpacing(8).createRowHelper(1);
        helper.addChild(Button.builder(Component.translatable("createWorld.tab.infs-farlands.button"),
                button -> {
                }).width(BUTTON_WIDTH).build());
    }
}
