package com.inf.farlands.client.gui.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 按声明生成的一行行参数文本框。
 *
 * <p>自持子控件、自己渲染与分发事件。要自建容器是因为外层 ScrollableLayout 的滚动容器在构造时就把内容
 * 叶子抄进私有列表，之后往内容布局里加控件既不会被画也收不到事件，而参数框数随选中的系统变化。
 *
 * <p>两处针对嵌套的补丁：
 * 一是行坐标。本控件的 x/y 由外层布局决定，装配时未必已定位，所以 setX 与 setY 都要触发一次行重排，
 * 渲染前再排一次兜底。
 * 二是滚轮。基类 AbstractScrollArea.mouseScrolled 恒返回 true，本组从不滚动，会吃掉外层页面的滚轮，
 * 所以直接返回 false。
 *
 * <p>没有声明时退化成单个自由文本框，预填一段示例，空文本等于空映射。类型化框预填声明的默认值文本，
 * 没有字面默认值的参数留空，空框的取值由该参数声明的空关键字决定。
 */
public final class ParamGroup extends AbstractContainerWidget {

    private static final int ROW_HEIGHT = 22;
    private static final int BOX_HEIGHT = 20;
    private static final int LABEL_GAP = 4;
    private static final int MIN_LABEL_WIDTH = 40;
    private static final int MIN_BOX_WIDTH = 80;
    private static final int LABEL_TOP = 6;
    /** 框内文本上限。原版 EditBox 的 maxLength 默认只有 32，贴一段参数 JSON 会被静默截断。 */
    private static final int MAX_TEXT_LENGTH = 1024;
    private static final String FREE_TEXT = "{\"seed\":{\"type\":\"long\",\"value\":0}}";
    /** 自由框接受的形状示例。引导句与排版在语言文件的 free.example 里，本常量是它的 %s 实参。 */
    private static final String FREE_GRAMMAR =
            "{\"type\":\"int\",\"value\":1}\n"
                    + "{\"type\":\"boolean\",\"value\":true}\n"
                    + "{\"type\":\"string\",\"value\":\"abc\"}\n"
                    + "{\"type\":\"enum\",\"value\":\"TYPE1\",\"enumClass\":\"com.example.Mode\"}";

    private final List<AbstractWidget> children = new ArrayList<>();
    private final int groupWidth;
    private int labelWidth = MIN_LABEL_WIDTH;
    private boolean free;
    private int rows;

    public ParamGroup(int width) {
        super(0, 0, width, 0, Component.empty(), AbstractScrollArea.defaultSettings(10));
        this.groupWidth = width;
    }

    /** 按声明重建。params 为 null 表示没有声明，退化成单个自由文本框。 */
    public void setParams(SystemParams params) {
        this.children.clear();
        this.free = params == null;
        Font font = Minecraft.getInstance().font;
        if (this.free) {
            this.rows = 1;
            EditBox box = new EditBox(font, this.groupWidth, BOX_HEIGHT,
                    Component.translatable("createWorld.tab.infs-farlands.param.free"));
            box.setMaxLength(MAX_TEXT_LENGTH);
            box.setValue(FREE_TEXT);
            box.setTooltip(Tooltip.create(Component.translatable(
                    "createWorld.tab.infs-farlands.param.free.example", FREE_GRAMMAR)));
            this.children.add(box);
        } else {
            this.labelWidth = this.labelWidth(font, params);
            this.rows = params.entries().size();
            for (SystemParamSpec spec : params.entries()) {
                StringWidget label = new StringWidget(Component.literal(spec.key()), font);
                EditBox box = new EditBox(font, this.boxWidth(), BOX_HEIGHT, Component.literal(spec.key()));
                box.setMaxLength(MAX_TEXT_LENGTH);
                box.setHint(Component.literal(spec.type().id()));
                box.setTooltip(Tooltip.create(this.tooltip(spec)));
                String text = spec.defaultText();
                if (text != null) {
                    box.setValue(text);
                }
                this.children.add(label);
                this.children.add(box);
            }
        }
        this.setHeight(this.rows * ROW_HEIGHT);
        this.layoutRows();
    }

    private int labelWidth(Font font, SystemParams params) {
        int widest = MIN_LABEL_WIDTH;
        for (SystemParamSpec spec : params.entries()) {
            widest = Math.max(widest, font.width(Component.literal(spec.key())) + LABEL_GAP);
        }
        return widest;
    }

    private int boxWidth() {
        return Math.max(MIN_BOX_WIDTH, this.groupWidth - this.labelWidth);
    }

    /** tooltip 第一行是「类型：值」，其余逐条列出该参数的映射；两行的排版都走语言文件。 */
    private Component tooltip(SystemParamSpec spec) {
        MutableComponent text = Component.translatable("createWorld.tab.infs-farlands.param.type",
                spec.type().id());
        for (Map.Entry<String, SystemParamSpec.Keyword> entry : spec.keywords().entrySet()) {
            text.append("\n").append(Component.translatable("createWorld.tab.infs-farlands.param.line",
                    entry.getKey(),
                    Component.translatable(entry.getValue().langKey())));
        }
        return text;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.layoutRows();
        for (AbstractWidget child : this.children) {
            child.extractRenderState(graphics, mouseX, mouseY, a);
        }
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        this.layoutRows();
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        this.layoutRows();
    }

    /** 本组从不滚动，收到滚轮就还给外层页面。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    private void layoutRows() {
        int x = this.getX();
        int y = this.getY();
        if (this.free) {
            if (!this.children.isEmpty()) {
                this.children.get(0).setPosition(x, y);
            }
            return;
        }
        for (int i = 0; i < this.children.size(); i += 2) {
            this.children.get(i).setPosition(x, y + LABEL_TOP);
            this.children.get(i + 1).setPosition(x + this.labelWidth, y);
            y += ROW_HEIGHT;
        }
    }

    @Override
    protected int contentHeight() {
        return this.rows * ROW_HEIGHT;
    }

    @Override
    public List<? extends AbstractWidget> children() {
        return Collections.unmodifiableList(this.children);
    }

    @Override
    public Collection<? extends NarratableEntry> getNarratables() {
        return this.children;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
