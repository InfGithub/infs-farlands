package com.inf.farlands.client.gui.demo;

import java.util.List;
import java.util.function.IntConsumer;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * 样式 D：点击后从控件下方浮出列表的下拉框。
 *
 * <p>画法参照 CommandSuggestions 的 SuggestionsList：列表只用 fill 与 text 画，选中项白色，其余
 * 灰色，不套背景贴图。原版没有可复用的下拉控件，SuggestionsList 又是内部类且构造要 Brigadier 的
 * 解析结果，所以自绘一份。选项是调用方给的一串文字，这里不关心它是什么。
 *
 * <p>展开的列表由 {@link Layer} 代画。26.1.2 只有 tooltip 能延后到全部控件之后绘制，普通控件没有
 * 延后绘制的入口，所以把 Layer 放在布局的最后一位，由它把当前展开的列表画在其余行之上，否则列表
 * 会被下面几行盖住。
 *
 * <p>命中判定覆写 isMouseOver，把展开后的列表也算进自己的矩形，否则点击与滚轮到不了本控件：屏幕
 * 的子分发先做 isMouseOver 再进 mouseClicked。
 *
 * <p>展开时若列表底边越过可视区，交给 {@link Revealer} 把内容滚上来：滚动容器的高度等于内容与
 * 可视区的较小值，列表画到内容下边就会被裁掉，光靠容器自己是看不见的。
 *
 * <p>关闭只支持再点按钮、选中、Esc 三种，点页面别处不关，原版的下拉也是靠外层屏幕显式转发事件
 * 才做到点外部关闭。
 *
 * <p>点击与回车走基类的 playDownSound，与按钮同一声，即 UI_BUTTON_CLICK。基类那一声原本由
 * AbstractWidget.mouseClicked 发出，本类覆写了它，所以这里自己补上；滚轮翻页不发声，与
 * CycleButton 一致。
 */
public final class DropdownSelect extends AbstractWidget {

    private static final int ROW_HEIGHT = 12;
    private static final int MAX_VISIBLE = 4;
    private static final int LIST_FILL = 0xD0000000;
    private static final int LIST_BORDER = 0xFFA0A0A0;
    private static final int TEXT_SELECTED = -1;
    private static final int TEXT_NORMAL = -5592406;

    private static final WidgetSprites SPRITES = new WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"));

    private final List<Component> options;
    private final Layer layer;
    private final Revealer revealer;
    private final IntConsumer onSelect;
    private int selected;
    private int highlight;
    private int offset;
    private boolean open;

    public DropdownSelect(Layer layer, Revealer revealer, int width, int height, List<Component> options,
            int selected, IntConsumer onSelect) {
        super(0, 0, width, height, Component.empty());
        this.layer = layer;
        this.revealer = revealer;
        this.options = List.copyOf(options);
        this.selected = selected;
        this.highlight = selected;
        this.onSelect = onSelect;
    }

    /** 当前选中项在选项列表里的下标。 */
    public int selectedIndex() {
        return this.selected;
    }

    private Font font() {
        return Minecraft.getInstance().font;
    }

    private int visibleRows() {
        return Math.min(this.options.size(), MAX_VISIBLE);
    }

    private int maxOffset() {
        return Math.max(0, this.options.size() - MAX_VISIBLE);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight(), ARGB.white(this.alpha));
        graphics.text(this.font(), this.options.get(this.selected), this.getX() + 4, this.getY() + 6, TEXT_SELECTED);
        if (this.isMouseOver(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    /** 由 Layer 调用，把展开的列表画在本控件下方。 */
    private void extractList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int listX = this.getX();
        int listY = this.getY() + this.getHeight();
        int rows = this.visibleRows();
        graphics.fill(listX - 1, listY - 1, listX + this.getWidth() + 1, listY + rows * ROW_HEIGHT + 1, LIST_BORDER);
        for (int i = 0; i < rows; i++) {
            int index = this.offset + i;
            int rowY = listY + i * ROW_HEIGHT;
            graphics.fill(listX, rowY, listX + this.getWidth(), rowY + ROW_HEIGHT, LIST_FILL);
            int color = index == this.highlight ? TEXT_SELECTED : TEXT_NORMAL;
            graphics.text(this.font(), this.options.get(index), listX + 2, rowY + 2, color);
        }
        if (this.isMouseOver(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!this.isActive()) {
            return false;
        }
        if (super.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        return this.open && this.isOverList(mouseX, mouseY);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        int listY = this.getY() + this.getHeight();
        return mouseX >= this.getX() && mouseX < this.getX() + this.getWidth()
                && mouseY >= listY && mouseY < listY + this.visibleRows() * ROW_HEIGHT;
    }

    /**
     * 列表展开着且指针落在列表内。供外层滚动区的滚轮判定用：那层取不到本类，只在自己收到滚轮时会问
     * 一遍 child，为真才把滚轮让给列表，见 {@code DropdownScrollWheelMixin}。
     */
    public boolean isOverOpenList(double mouseX, double mouseY) {
        return this.open && this.isOverList(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.isActive()) {
            return false;
        }
        if (this.open && this.isOverList(event.x(), event.y())) {
            int index = this.offset + (int) ((event.y() - this.getY() - this.getHeight()) / ROW_HEIGHT);
            if (index >= 0 && index < this.options.size()) {
                this.selected = index;
                this.highlight = index;
                this.onSelect.accept(index);
            }
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            this.close();
            return true;
        }
        if (event.button() == 0) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            if (this.open) {
                this.close();
            } else {
                this.open = true;
                this.highlight = this.selected;
                // 每次展开都从第 0 项开始显示，不把选中项滚进可见区。代价是选中项靠后时展开看不到高亮。
                this.offset = 0;
                this.layer.setOpen(this);
                this.revealer.reveal(this.getY() + this.getHeight() + this.visibleRows() * ROW_HEIGHT);
            }
            return true;
        }
        return false;
    }

    private void close() {
        this.collapse();
        this.layer.clear(this);
    }

    /** 只收起自己，不通知 Layer。Layer 改指别的下拉时用它，那时 Layer.open 已经换了目标。 */
    private void collapse() {
        this.open = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.open) {
            return false;
        }
        int step = scrollY > 0.0 ? -1 : 1;
        this.offset = Mth.clamp(this.offset + step, 0, this.maxOffset());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.open) {
            return false;
        }
        if (event.isEscape()) {
            this.close();
            return true;
        }
        if (event.isUp() || event.isDown()) {
            this.highlight = Mth.clamp(this.highlight + (event.isUp() ? -1 : 1), 0, this.options.size() - 1);
            if (this.highlight < this.offset) {
                this.offset = this.highlight;
            } else if (this.highlight >= this.offset + MAX_VISIBLE) {
                this.offset = this.highlight - MAX_VISIBLE + 1;
            }
            return true;
        }
        if (event.isConfirmation()) {
            this.selected = this.highlight;
            this.onSelect.accept(this.selected);
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            this.close();
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.options.get(this.selected));
    }

    /** 把展开的列表滚进可视区。入参是列表底边的屏幕 y。 */
    @FunctionalInterface
    public interface Revealer {
        void reveal(int listBottom);
    }

    /**
     * 浮层代画者。放在布局的最后一位，所以它的 extractRenderState 在其余行之后执行，展开的列表
     * 才不会被下面几行盖住。自身是零尺寸，命中判定恒假，不参与鼠标分发。同一时刻只有一个下拉展开，
     * 所以一个 Layer 够用。
     */
    public static final class Layer extends AbstractWidget {

        private DropdownSelect open;

        public Layer() {
            super(0, 0, 0, 0, Component.empty());
        }

        private void setOpen(DropdownSelect dropdown) {
            DropdownSelect previous = this.open;
            this.open = dropdown;
            if (previous != null && previous != dropdown) {
                previous.collapse();
            }
        }

        private void clear(DropdownSelect dropdown) {
            if (this.open == dropdown) {
                this.open = null;
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            DropdownSelect target = this.open;
            if (target == null) {
                return;
            }
            if (!target.visible) {
                target.collapse();
                this.open = null;
                return;
            }
            target.extractList(graphics, mouseX, mouseY);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
