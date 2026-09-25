package com.inf.farlands.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.FamilyPage;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemsData;
import com.inf.farlands.terrain.registry.SystemsIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 创建世界页签下面全部可编辑状态的暂存处：三页 × 四族各自选中的系统，以及该系统每个参数的文本。
 *
 * <p>放静态持有者而不是页签实例上，是因为页签在 CreateWorldScreen.init 里构造，界面重建会重建页签，
 * 实例字段存不住。
 *
 * <p>本类依赖 SystemToast 与 Minecraft，故留在 client 源集；键用的两个枚举在 main 源集。
 *
 * <p>两个入口预填的东西不同：新建与测试世界从空开始，界面走各族默认；重建已有世界按该世界的
 * systems.dat 预填，缺项与读取失败都退回默认，读取失败另弹一个 toast。
 */
public final class CreatingWorldSystemsConfig {

    /** 没有参数声明的系统退化成自由文本框，它的文本用这个键存，与参数名不可能冲突。 */
    public static final String FREE_KEY = "";

    /** 页 -> 族 -> 选中的系统。 */
    private static final Map<FamilyPage, Map<FamilyKind, SystemId>> SYSTEMS = new ConcurrentHashMap<>();

    /** 页 -> 族 -> 系统 -> 参数名 -> 文本。按系统分组，切换系统后原来填的文本仍留着。 */
    private static final Map<FamilyPage, Map<FamilyKind, Map<SystemId, Map<String, String>>>> TEXT = new ConcurrentHashMap<>();

    private CreatingWorldSystemsConfig() {
    }

    /** 清空全部暂存。打开创建世界页、以及重建世界读盘前各调用一次。 */
    public static void reset() {
        SYSTEMS.clear();
        TEXT.clear();
    }

    /** 该页该族选中的系统，没选过返回 null。 */
    public static SystemId system(FamilyPage page, FamilyKind kind) {
        return inner(SYSTEMS, page).get(kind);
    }

    public static void select(FamilyPage page, FamilyKind kind, SystemId system) {
        inner(SYSTEMS, page).put(kind, system);
    }

    /** 该页该族该系统的参数文本。没有条目时返回空视图，调用方不必判空。 */
    public static Map<String, String> text(FamilyPage page, FamilyKind kind, SystemId system) {
        Map<SystemId, Map<String, String>> bySystem = innerText(page, kind);
        Map<String, String> held = bySystem.get(system);
        return held == null ? Map.of() : held;
    }

    /** 写一条参数文本。只在真的有编辑时调用，空框不落条目。 */
    public static void putText(FamilyPage page, FamilyKind kind, SystemId system, String key, String value) {
        innerText(page, kind).computeIfAbsent(system, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    private static <K, V> Map<K, V> inner(Map<FamilyPage, Map<K, V>> outer, FamilyPage page) {
        return outer.computeIfAbsent(page, k -> new ConcurrentHashMap<>());
    }

    /** TEXT 比 SYSTEMS 多一层，单独写一个重载，免得泛型推断失败。 */
    private static Map<SystemId, Map<String, String>> innerText(FamilyPage page, FamilyKind kind) {
        return inner(TEXT, page).computeIfAbsent(kind, k -> new ConcurrentHashMap<>());
    }

    /**
     * 按已有世界的 systems.dat 预填。文件不存在或读取失败时，映射留空，界面退回默认；读取失败另弹
     * 一个 toast。
     *
     * @param worldRoot 世界根目录，由 LevelStorageAccess.getLevelPath(LevelResource.ROOT) 给出
     */
    public static void prefillFrom(Path worldRoot) {
        Path file = SystemsData.ID.withSuffix(".dat")
                .resolveAgainst(worldRoot.resolve(LevelResource.DATA.id()));
        try {
            if (Files.exists(file)) {
                apply(SystemsIO.read(file));
            }
        } catch (RuntimeException e) {
            toastReadFailure(e);
        }
    }

    private static void apply(SystemsData data) {
        for (Map.Entry<Identifier, SystemsData.LevelSelection> level : data.levels().entrySet()) {
            Optional<FamilyPage> page = FamilyPage.ofDimension(level.getKey());
            if (page.isPresent()) {
                applyLevel(page.get(), level.getValue());
            }
        }
    }

    private static void applyLevel(FamilyPage page, SystemsData.LevelSelection selection) {
        applySystem(page, FamilyKind.TERRAIN, selection.terrain());
        applySystem(page, FamilyKind.BIOME, selection.biome());
        applySystem(page, FamilyKind.SURFACE, selection.surface());
        applySystem(page, FamilyKind.CARVER, selection.carver());
    }

    private static void applySystem(FamilyPage page, FamilyKind kind, SystemsData.SystemSelection selection) {
        SystemId id = new SystemId(selection.id());
        select(page, kind, id);
        for (Map.Entry<String, SystemsData.Arg> arg : selection.args().entrySet()) {
            putText(page, kind, id, arg.getKey(), arg.getValue().value().getValue().toString());
        }
    }

    /** 读取失败弹一段中英可译的提示，正文带异常消息便于定位。 */
    private static void toastReadFailure(RuntimeException e) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.add(minecraft.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("createWorld.tab.infs-farlands.error.read.title"),
                Component.translatable("createWorld.tab.infs-farlands.error.read.message",
                        String.valueOf(e.getMessage())));
    }
}
