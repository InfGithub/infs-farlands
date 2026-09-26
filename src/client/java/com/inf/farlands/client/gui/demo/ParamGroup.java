package com.inf.farlands.client.gui.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.inf.farlands.client.gui.CreatingWorldSystemsConfig;
import com.inf.farlands.terrain.registry.SystemParamSpec;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.SystemSelectionParser;

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
    /** 文本不合声明时的字色，合法时换回 EditBox.DEFAULT_TEXT_COLOR。 */
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final String FREE_TEXT = "{\"seed\":{\"type\":\"long\",\"value\":0}}";
    /** 自由框接受的形状示例。引导句与排版在语言文件的 free.example 里，本常量是它的 %s 实参。 */
    private static final String FREE_GRAMMAR =
            "{\"type\":\"int\",\"value\":1}\n"
                    + "{\"type\":\"boolean\",\"value\":true}\n"
                    + "{\"type\":\"string\",\"value\":\"abc\"}\n"
                    + "{\"type\":\"enum\",\"value\":\"TYPE1\",\"enumClass\":\"com.example.Mode\"}";

    private final List<AbstractWidget> children = new ArrayList<>();
    /** 参数名到它的框，含自由框那个专用键。切换系统前用它把当前文本整体刷一遍。 */
    private final Map<String, EditBox> texts = new LinkedHashMap<>();
    /** 参数名到它的采集回调，reload 时按当前文本重放一遍。 */
    private final Map<String, BiConsumer<String, String>> reporters = new LinkedHashMap<>();
    /** 参数名到它的声明，逐框校验用；自由框不进这张表。 */
    private final Map<String, SystemParamSpec> specs = new LinkedHashMap<>();
    /** 参数名到建框时的原始 tooltip，标红时在它后面追加错因，恢复时换回来。 */
    private final Map<String, Component> baseTooltips = new LinkedHashMap<>();
    private final int groupWidth;
    private int labelWidth = MIN_LABEL_WIDTH;
    private boolean free;
    private int rows;

    public ParamGroup(int width) {
        super(0, 0, width, 0, Component.empty(), AbstractScrollArea.defaultSettings(10));
        this.groupWidth = width;
    }

    /** 按声明重建，不接文本变更。 */
    public void setParams(SystemParams params) {
        this.setParams(params, Map.of(), null);
    }

    /**
     * 按声明重建。params 为 null 表示没有声明，退化成单个自由文本框。
     *
     * <p>saved 是该项上次留下的文本，键与 onText 收到的键一致，自由框用专用的那个键。有则预填，
     * 没有就用声明给的默认文本。onText 为 null 表示不采集。
     *
     * <p>顺序固定为先 setValue 再 setResponder：setResponder 只写字段，而 setValue 会走
     * onValueChange 回调 responder，先挂上会把预填也报一遍。
     */
    public void setParams(SystemParams params, Map<String, String> saved, BiConsumer<String, String> onText) {
        this.children.clear();
        this.texts.clear();
        this.reporters.clear();
        this.specs.clear();
        this.baseTooltips.clear();
        this.free = params == null;
        Font font = Minecraft.getInstance().font;
        if (this.free) {
            this.rows = 1;
            EditBox box = new EditBox(font, this.groupWidth, BOX_HEIGHT,
                    Component.translatable("createWorld.tab.infs-farlands.param.free"));
            box.setMaxLength(MAX_TEXT_LENGTH);
            box.setValue(saved.getOrDefault(CreatingWorldSystemsConfig.FREE_KEY, FREE_TEXT));
            Component base = Component.translatable(
                    "createWorld.tab.infs-farlands.param.free.example", FREE_GRAMMAR);
            box.setTooltip(Tooltip.create(base));
            this.baseTooltips.put(CreatingWorldSystemsConfig.FREE_KEY, base);
            box.setResponder(value -> {
                this.mark(CreatingWorldSystemsConfig.FREE_KEY, box, SystemSelectionParser.problemFree(value));
                if (onText != null) {
                    onText.accept(CreatingWorldSystemsConfig.FREE_KEY, value);
                }
            });
            if (onText != null) {
                this.reporters.put(CreatingWorldSystemsConfig.FREE_KEY, onText);
            }
            this.texts.put(CreatingWorldSystemsConfig.FREE_KEY, box);
            this.children.add(box);
        } else {
            this.labelWidth = this.labelWidth(font, params);
            this.rows = params.entries().size();
            for (SystemParamSpec spec : params.entries()) {
                StringWidget label = new StringWidget(Component.literal(spec.key()), font);
                EditBox box = new EditBox(font, this.boxWidth(), BOX_HEIGHT, Component.literal(spec.key()));
                box.setMaxLength(MAX_TEXT_LENGTH);
                box.setHint(Component.literal(spec.type().id()));
                Component base = this.tooltip(spec);
                box.setTooltip(Tooltip.create(base));
                String text = saved.getOrDefault(spec.key(), spec.defaultText());
                if (text != null) {
                    box.setValue(text);
                }
                box.setResponder(value -> {
                    this.mark(spec.key(), box, SystemSelectionParser.problem(spec, value));
                    if (onText != null) {
                        onText.accept(spec.key(), value);
                    }
                });
                if (onText != null) {
                    this.reporters.put(spec.key(), onText);
                }
                this.specs.put(spec.key(), spec);
                this.baseTooltips.put(spec.key(), base);
                this.texts.put(spec.key(), box);
                this.children.add(label);
                this.children.add(box);
            }
        }
        this.setHeight(this.rows * ROW_HEIGHT);
        this.layoutRows();
        this.validateAll();
    }

    /** 建好框后按当前文本校验一遍：预填值也要标出来，但不回写采集回调。 */
    private void validateAll() {
        for (Map.Entry<String, EditBox> entry : this.texts.entrySet()) {
            SystemParamSpec spec = this.specs.get(entry.getKey());
            String problem = spec == null
                    ? SystemSelectionParser.problemFree(entry.getValue().getValue())
                    : SystemSelectionParser.problem(spec, entry.getValue().getValue());
            this.mark(entry.getKey(), entry.getValue(), problem);
        }
    }

    /**
     * 标出或恢复一个框：非法时红字并把错因追加到原始 tooltip 之后，合法时换回默认字色与原始 tooltip。
     */
    private void mark(String key, EditBox box, String problem) {
        Component base = this.baseTooltips.get(key);
        if (problem == null) {
            box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            if (base != null) {
                box.setTooltip(Tooltip.create(base));
            }
            return;
        }
        box.setTextColor(INVALID_TEXT_COLOR);
        if (base != null) {
            box.setTooltip(Tooltip.create(Component.empty().append(base).append("\n").append(problem)));
        }
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

    /**
     * 把当前每个框的文本报一遍给采集回调，用于切换系统前把本组填的内容落到暂存处。EditBox 不暴露
     * 已设的 responder，所以这里自己留一份回调，按框的当前文本逐条上报。
     */
    public void reload() {
        for (Map.Entry<String, EditBox> entry : this.texts.entrySet()) {
            BiConsumer<String, String> report = this.reporters.get(entry.getKey());
            if (report != null) {
                report.accept(entry.getKey(), entry.getValue().getValue());
            }
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
