package com.inf.farlands.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.inf.farlands.client.gui.demo.DropdownSelect;
import com.inf.farlands.client.gui.demo.ParamGroup;
import com.inf.farlands.terrain.registry.BiomeDefaultSystem;
import com.inf.farlands.terrain.registry.CarverDefaultSystem;
import com.inf.farlands.terrain.registry.FamilyPage;
import com.inf.farlands.terrain.registry.SurfaceDefaultSystem;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemRegistries;
import com.inf.farlands.terrain.registry.SystemParams;
import com.inf.farlands.terrain.registry.TerrainDefaultSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.CommonLayouts;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 创建世界页的附加页签。页签行是原版样式 B 的分页，即 TabButton 排成的一行；主世界页里逐族放一个
 * 样式 D 的下拉框，选项是注册表里该族系统的 id，下拉下面按该系统的声明生成一组参数文本框。
 *
 * <p>原版 TabNavigationBar 在这里用不了：它的 arrangeElements 把内部布局钉在屏幕 y 等于 0 的位置，
 * 而它不是 AbstractWidget，装不进页签的子控件列表。这里用同一套 TabButton 自排一行，贴图、选中时
 * 下移三像素、焦点下划线都与原版一致，只少了页签行左右两段分隔线。原版 TabButton 自己不响应点击，
 * 选中由 TabNavigationBar 设置焦点时驱动，所以这里补上点击即切换。
 *
 * <p>三页的内容都放进同一个 FrameLayout，随本页一次性挂上屏幕，切换页只改各页叶子的 visible，不动
 * 屏幕的控件列表。整块内容装在 ScrollableLayout 里，靠页签区域的左上摆放，高 GUI 缩放下可滚动。
 *
 * <p>下拉的浮层画在 FrameLayout 的最后一位：那一层由 DropdownSelect.Layer 承担，因此它的绘制在
 * 各页内容之后，展开的列表盖在参数框之上。
 *
 * <p>下拉宽度按选项文字自动算，四个框共用全部选项里最宽的那个加内边距。主世界页正文最后留出
 * POPUP_ROOM 的空白，滚动容器的高度才装得下最后一条展开的列表；可视区不够时由 Revealer 滚上来。
 * 选中项变化会改变参数框行数，所以那时要重跑一次布局。
 */
public class FarlandsTab implements Tab {

    private static final Component TITLE = Component.translatable("createWorld.tab.infs-farlands.title");
    private static final Component OVERWORLD_TITLE = Component
            .translatable("createWorld.tab.infs-farlands.page.overworld");
    private static final Component NETHER_TITLE = Component
            .translatable("createWorld.tab.infs-farlands.page.the_nether");
    private static final Component END_TITLE = Component.translatable("createWorld.tab.infs-farlands.page.the_end");
    private static final int MARGIN = 16;
    private static final int TAB_WIDTH = 68;
    private static final int TAB_HEIGHT = 24;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_PADDING = 8;
    private static final int MIN_FIELD_WIDTH = 120;
    private static final int MAX_FIELD_WIDTH = 300;
    /** 4 行各 12 加边框与余量：正文末尾要留出的浮层高度。 */
    private static final int POPUP_ROOM = 52;
    private static final int REVEAL_SLACK = 4;
    /**
     * ScrollableLayout 为滚动条预留的总宽度。它的 reserveStrategy 是 BOTH，两侧各留「间距 4 加滚动条
     * 宽 6」，容器总宽等于内容宽与最小宽的较大者再加这两份预留，所以撑宽度时要扣掉这一项。
     */
    private static final int SCROLLBAR_RESERVE = 2 * (4 + 6);

    private final ScrollableLayout scroll;
    private final AbstractScrollArea viewport;
    private ScreenRectangle lastArea;

    public FarlandsTab() {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        DropdownSelect.Layer layer = new DropdownSelect.Layer();
        DropdownSelect.Revealer revealer = this::reveal;

        LinearLayout strip = LinearLayout.horizontal().spacing(2);
        TabManager manager = new TabManager(widget -> {
        }, widget -> {
        }, page -> ((DemoPage) page).show(), page -> {
            if (page != null) {
                ((DemoPage) page).hide();
            }
        });

        List<DemoPage> pages = List.of(
                new DemoPage(OVERWORLD_TITLE, this.familyBody(font, FamilyPage.OVERWORLD, layer, revealer)),
                new DemoPage(NETHER_TITLE, this.familyBody(font, FamilyPage.THE_NETHER, layer, revealer)),
                new DemoPage(END_TITLE, this.familyBody(font, FamilyPage.THE_END, layer, revealer)));

        FrameLayout bodies = new FrameLayout();
        for (DemoPage page : pages) {
            page.hide();
            strip.addChild(new DemoTabButton(manager, page, TAB_WIDTH));
            bodies.addChild(page.root());
        }
        bodies.addChild(layer);

        GridLayout content = new GridLayout().rowSpacing(8);
        content.addChild(strip, 0, 0);
        content.addChild(bodies, 1, 0);
        this.scroll = new ScrollableLayout(minecraft, content, 0);
        this.viewport = this.findViewport();
        manager.setCurrentTab(pages.get(0), false);
    }

    /** 一个页：四族系统各一块，块内是标题、下拉、以及按声明生成的参数框。默认项按 page 从各族表取。 */
    private LinearLayout familyBody(Font font, FamilyPage page, DropdownSelect.Layer layer,
            DropdownSelect.Revealer revealer) {
        List<SystemId> biomeIds = SystemRegistries.biomeIds();
        List<SystemId> terrainIds = SystemRegistries.terrainIds();
        List<SystemId> surfaceIds = SystemRegistries.surfaceIds();
        List<SystemId> carverIds = SystemRegistries.carverIds();
        int width = this.fieldWidth(font, List.of(biomeIds, terrainIds, surfaceIds, carverIds));

        LinearLayout body = LinearLayout.vertical().spacing(8);
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.biome"),
                biomeIds, BiomeDefaultSystem.defaultFor(page), SystemRegistries::biomeClassOf, layer,
                revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.terrain"),
                terrainIds, TerrainDefaultSystem.defaultFor(page), SystemRegistries::terrainClassOf, layer,
                revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.surface"),
                surfaceIds, SurfaceDefaultSystem.defaultFor(page), SystemRegistries::surfaceClassOf, layer,
                revealer, width));
        body.addChild(this.familyBlock(font, Component.translatable("createWorld.tab.infs-farlands.family.carver"),
                carverIds, CarverDefaultSystem.defaultFor(page), SystemRegistries::carverClassOf, layer,
                revealer, width));
        body.addChild(SpacerElement.height(POPUP_ROOM));
        return body;
    }

    /** 一个族块：标题在上，下面是下拉与它对应的参数组。defaultId 是下拉的初始选中项。 */
    private Layout familyBlock(Font font, Component title, List<SystemId> ids, SystemId defaultId,
            Function<SystemId, Class<?>> classOf, DropdownSelect.Layer layer, DropdownSelect.Revealer revealer,
            int width) {
        int index = defaultIndex(ids, defaultId);
        ParamGroup group = new ParamGroup(width);
        this.applySelection(group, classOf, ids.get(index));
        LinearLayout control = LinearLayout.vertical().spacing(4);
        control.addChild(new DropdownSelect(layer, revealer, width, FIELD_HEIGHT, this.idOptions(ids), index,
                i -> this.applySelection(group, classOf, ids.get(i))));
        control.addChild(group);
        return CommonLayouts.labeledElement(font, control, title);
    }

    /** 默认项在该族列表里的下标。列表里没有它时退回第一项。 */
    private static int defaultIndex(List<SystemId> ids, SystemId defaultId) {
        return Math.max(0, ids.indexOf(defaultId));
    }

    private void applySelection(ParamGroup group, Function<SystemId, Class<?>> classOf, SystemId id) {
        group.setParams(SystemParams.declaredBy(classOf.apply(id)));
        this.relayout();
    }

    /** 参数框行数变了，内容高度跟着变，所以要重跑一次布局，否则会被裁在旧高度里。 */
    private void relayout() {
        ScreenRectangle area = this.lastArea;
        if (area != null) {
            this.doLayout(area);
        }
    }

    /**
     * 四个下拉共用一个宽度：取全部选项里最宽的那个加内边距，再夹在上下限之间。文字宽度由字体量，
     * 不写死。
     */
    private int fieldWidth(Font font, List<List<SystemId>> families) {
        int widest = 0;
        for (List<SystemId> ids : families) {
            for (SystemId id : ids) {
                widest = Math.max(widest, font.width(Component.literal(id.toString())));
            }
        }
        return Mth.clamp(widest + FIELD_PADDING, MIN_FIELD_WIDTH, MAX_FIELD_WIDTH);
    }

    /** 选项文字取 SystemId.toString，即带命名空间的完整 id，例如 infs-farlands:void_noise_system。 */
    private List<Component> idOptions(List<SystemId> ids) {
        List<Component> options = new ArrayList<>(ids.size());
        for (SystemId id : ids) {
            options.add(Component.literal(id.toString()));
        }
        return options;
    }

    /** 滚动容器是 ScrollableLayout 的私有内部类，只能从 visitChildren 里认出来。 */
    private AbstractScrollArea findViewport() {
        AbstractScrollArea[] found = new AbstractScrollArea[1];
        this.scroll.visitChildren(child -> {
            if (child instanceof AbstractScrollArea area) {
                found[0] = area;
            }
        });
        return found[0];
    }

    /** 展开的列表越过可视区下边界时，把内容向上滚，让列表整体落在可视区里。 */
    private void reveal(int listBottom) {
        AbstractScrollArea area = this.viewport;
        if (area == null) {
            return;
        }
        int overflow = listBottom + REVEAL_SLACK - area.getBottom();
        if (overflow > 0) {
            area.setScrollAmount(area.scrollAmount() + overflow);
        }
    }

    @Override
    public Component getTabTitle() {
        return TITLE;
    }

    @Override
    public Component getTabExtraNarration() {
        return Component.empty();
    }

    @Override
    public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
        this.scroll.visitWidgets(childrenConsumer);
    }

    /**
     * 整块内容靠页签区域的左上摆放，上下左右各留 MARGIN 像素。滚动区的最小宽度撑到可用宽度，否则
     * 容器宽度贴着内容，滚动条会画在内容右边而不是页面右边。
     */
    @Override
    public void doLayout(ScreenRectangle screenRectangle) {
        this.lastArea = screenRectangle;
        int available = Math.max(0, screenRectangle.width() - MARGIN * 2);
        this.scroll.setMinWidth(Math.max(0, available - SCROLLBAR_RESERVE));
        this.scroll.arrangeElements();
        this.scroll.setMaxHeight(Math.max(0, screenRectangle.height() - MARGIN * 2));
        this.scroll.setPosition(screenRectangle.left() + MARGIN, screenRectangle.top() + MARGIN);
    }

    /**
     * 页签行里的一格。原版 TabButton 自己不响应点击，选中由 TabNavigationBar 设置焦点时驱动；
     * 这里独立成行，所以补上点击即切换。
     */
    private static final class DemoTabButton extends TabButton {

        private final TabManager manager;

        private DemoTabButton(TabManager manager, Tab tab, int width) {
            super(manager, tab, width, TAB_HEIGHT);
            this.manager = manager;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.manager.setCurrentTab(this.tab(), true);
        }
    }

    /**
     * 一页：标题加内容。内容随本页一次性挂上屏幕，切换页只改这一页全部叶子的 visible。内容既可能
     * 是单个控件，也可能是一棵布局，所以按 LayoutElement 收，叶子在构造时取一次。
     */
    private static final class DemoPage implements Tab {

        private final Component title;
        private final LayoutElement root;
        private final List<AbstractWidget> leaves = new ArrayList<>();

        private DemoPage(Component title, LayoutElement root) {
            this.title = title;
            this.root = root;
            root.visitWidgets(this.leaves::add);
        }

        @Override
        public Component getTabTitle() {
            return this.title;
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.empty();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
            this.root.visitWidgets(childrenConsumer);
        }

        @Override
        public void doLayout(ScreenRectangle screenRectangle) {
        }

        private LayoutElement root() {
            return this.root;
        }

        private void show() {
            this.setVisible(true);
        }

        private void hide() {
            this.setVisible(false);
        }

        private void setVisible(boolean visible) {
            for (AbstractWidget leaf : this.leaves) {
                leaf.visible = visible;
            }
        }
    }
}
