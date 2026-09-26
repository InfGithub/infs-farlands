package com.inf.farlands.client.register.command;

import com.inf.farlands.InfsFarlands;
import com.inf.farlands.command.CommandRegistrationEvent;
import com.inf.farlands.register.command.FarLandsCommands;
import com.inf.farlands.util.window.WindowedChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;

/**
 * 客户端诊断命令族 {@code /farlands client section ...}：与服务端 FLDUMP 同格式输出客户端侧
 * 的光照层、群系与方块数据，用于对比定位同步断链。
 *
 * <p>
 * 本类引用客户端类 {@link Minecraft}，只在客户端加载；服务端不注册、不加载这些命令。
 *
 * <p>
 * 移植自上一版本（NeoForge 1.21.1），适配点：{@code RegisterClientCommandsEvent} →
 * {@link CommandRegistrationEvent}（客户端路径的 {@code getCommandSelection()} 为
 * null）、
 * {@code InfFarlands} → {@link InfsFarlands}，以及客户端光照引擎的取法——
 * 26.1.2 的 {@code ClientLevel} 没有 {@code getLightEngine()}，改由
 * {@code level.getChunkSource().getLightEngine()} 取（{@code ClientChunkCache}
 * 持有）。
 */
public class ClientFarLandsCommands {

    /** 渲染侧内省的日志前缀，供 grep 定位。 */
    private static final String RENDER_DUMP = "FLRENDER";

    public static void register(CommandRegistrationEvent event) {
        event.getDispatcher().register(
                Commands.literal("farlands")
                        .then(Commands.literal("client")
                                .then(Commands.literal("section")
                                        .then(Commands.literal("light")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpClient(ctx.getSource()))))
                                        .then(Commands.literal("biome")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpBiomeClient(ctx.getSource()))))
                                        .then(Commands.literal("block")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpBlocksClient(ctx.getSource()))))
                                        .then(Commands.literal("render")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpRenderClient(ctx.getSource()))))
                                        .then(Commands.literal("size")
                                                .then(Commands.literal("dump")
                                                        .executes(ctx -> dumpSectionSizeClient(ctx.getSource())))))));
    }

    private static int dumpClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            LevelLightEngine le = mc.level.getChunkSource().getLightEngine();

            InfsFarlands.LOGGER.info("FLDUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpLayer(le, LightLayer.SKY, sec);
            FarLandsCommands.dumpLayer(le, LightLayer.BLOCK, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.light.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("FLDUMP client err", e);
        }
        return 1;
    }

    private static int dumpBiomeClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BIODUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpBiomes(mc.level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.biome.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BIODUMP client err", e);
        }
        return 1;
    }

    /** 方块数据独立命令：/farlands client section block dump。 */
    private static int dumpBlocksClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("BLOCKDUMP client pos={},{},{} sec={},{},{}",
                    pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());
            FarLandsCommands.dumpBlocks(mc.level, sec);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.block.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("BLOCKDUMP client err", e);
        }
        return 1;
    }

    /**
     * 客户端全部已加载 chunk 的 section 总数，即 Σ windowedAllSections().size()。
     *
     * <p>
     * 客户端没有公开的遍历入口：ClientChunkCache.storage 与它内部 Storage.chunks 都是 private，
     * 两跳反射取 AtomicReferenceArray。storage 在视距变化时整体换实例，命令跑在客户端线程，读一次
     * 即稳定快照。
     */
    private static int dumpSectionSizeClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return 1;
            }
            AtomicReferenceArray<?> chunks = minecraft$clientChunks(mc.level.getChunkSource());
            int count = 0;
            long sections = 0L;
            for (int i = 0; i < chunks.length(); i++) {
                if (chunks.get(i) instanceof LevelChunk lc) {
                    count++;
                    sections += ((WindowedChunk) lc).windowedAllSections().size();
                }
            }
            int chunkCount = count;
            long sectionCount = sections;
            InfsFarlands.LOGGER.info("SIZEDUMP client dim={} chunks={} sections={}",
                    mc.level.dimension().identifier(), chunkCount, sectionCount);
            source.sendSuccess(() -> Component.translatable("commands.infs-farlands.client.section.size.dump",
                    chunkCount, sectionCount), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("SIZEDUMP client err", e);
        }
        return 1;
    }

    private static AtomicReferenceArray<?> minecraft$clientChunks(ClientChunkCache cache) {
        try {
            Object storage = F_CLIENT_STORAGE.get(cache);
            return (AtomicReferenceArray<?>) F_STORAGE_CHUNKS.get(storage);
        } catch (Exception e) {
            throw new RuntimeException("farlands: ClientChunkCache.storage.chunks", e);
        }
    }

    /**
     * 渲染侧内省：打印玩家所在 section 的槽位归属、脏标记、mesh 与可见集、octree 归属。
     *
     * <p>
     * 方块在客户端逻辑层有数据却不渲染时，需要区分三种互斥情形：该 section 没进
     * {@code visibleSections}、进了但 mesh 为空、mesh 非空却不可见。三者在静态代码里读不出来。
     *
     * <p>
     * 只查玩家所在 section。渲染槽位、可见集与 octree 都只在该 chunk 真实加载并参与渲染时
     * 才有内容，读数因此取当前位置。
     */
    private static int dumpRenderClient(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.levelRenderer == null) {
                return 1;
            }
            BlockPos pos = mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            InfsFarlands.LOGGER.info("{} client pos={},{},{} section={},{},{}",
                    RENDER_DUMP, pos.getX(), pos.getY(), pos.getZ(), sec.x(), sec.y(), sec.z());

            LevelRenderer renderer = mc.levelRenderer;
            ViewArea viewArea = minecraft$viewArea(renderer);
            if (viewArea == null) {
                InfsFarlands.LOGGER.info("{}   viewArea=<absent>", RENDER_DUMP);
                return 1;
            }
            SectionPos camera = viewArea.getCameraSectionPos();
            InfsFarlands.LOGGER.info("{}   cameraSection={} viewDistance={}",
                    RENDER_DUMP,
                    camera == null ? "<absent>"
                            : camera.x() + "," + camera.y() + "," + camera.z(),
                    viewArea.getViewDistance());

            List<?> visible = minecraft$visibleSections(renderer);
            if (visible != null) {
                InfsFarlands.LOGGER.info("{}   visibleCount={}", RENDER_DUMP, visible.size());
            }

            SectionRenderDispatcher.RenderSection section = minecraft$renderSectionAt(viewArea, pos);
            if (section == null) {
                InfsFarlands.LOGGER.info("{}   slot=<absent>", RENDER_DUMP);
                return 1;
            }

            SectionPos node = SectionPos.of(section.getSectionNode());
            BlockPos origin = section.getRenderOrigin();
            AABB bb = section.getBoundingBox();
            InfsFarlands.LOGGER.info("{}   node={},{},{} origin={},{},{}",
                    RENDER_DUMP, node.x(), node.y(), node.z(),
                    origin.getX(), origin.getY(), origin.getZ());
            InfsFarlands.LOGGER.info("{}   bb={},{},{} .. {},{},{}",
                    RENDER_DUMP, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
            InfsFarlands.LOGGER.info("{}   dirty={} fromPlayer={} prevEmpty={} allNeighbors={}",
                    RENDER_DUMP, section.isDirty(), section.isDirtyFromPlayer(),
                    section.wasPreviouslyEmpty(), section.hasAllNeighbors());

            SectionMesh mesh = section.getSectionMesh();
            InfsFarlands.LOGGER.info("{}   mesh={} hasRenderableLayers={}",
                    RENDER_DUMP, minecraft$meshName(mesh),
                    mesh != null && mesh.hasRenderableLayers());
            if (mesh != null) {
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                    InfsFarlands.LOGGER.info("{}   layer={} draw={} indexCount={}",
                            RENDER_DUMP, layer, draw == null ? "absent" : "present",
                            draw == null ? -1 : draw.indexCount());
                }
            }

            if (visible != null) {
                InfsFarlands.LOGGER.info("{}   inVisibleSections={}", RENDER_DUMP, visible.contains(section));
            }
            SectionOcclusionGraph graph = renderer.getSectionOcclusionGraph();
            SectionOcclusionGraph.Node graphNode = graph == null ? null : graph.getNode(section);
            InfsFarlands.LOGGER.info("{}   octreeNode={}", RENDER_DUMP,
                    graphNode == null ? "absent" : "present");
            if (graph != null) {
                InfsFarlands.LOGGER.info("{}   sog needsFullUpdate={} fullUpdateTask={} needsFrustumUpdate={}",
                        RENDER_DUMP, minecraft$needsFullUpdate(graph), minecraft$fullUpdateTaskDone(graph),
                        minecraft$needsFrustumUpdate(graph));
                Object root = minecraft$octreeRoot(graph.getOctree());
                if (root instanceof Octree.Node rootNode) {
                    AABB rootBb = rootNode.getAABB();
                    InfsFarlands.LOGGER.info("{}   octreeRoot={},{},{} .. {},{},{}",
                            RENDER_DUMP, rootBb.minX, rootBb.minY, rootBb.minZ,
                            rootBb.maxX, rootBb.maxY, rootBb.maxZ);
                }
            }
            source.sendSuccess(
                    () -> Component.translatable("commands.infs-farlands.client.section.render.dump"), false);
        } catch (Exception e) {
            InfsFarlands.LOGGER.error("{} err", RENDER_DUMP, e);
        }
        return 1;
    }

    /** mesh 具体类型。UNCOMPILED 与 EMPTY 都是 SectionMesh 的匿名实例，按常量身份区分。 */
    private static String minecraft$meshName(SectionMesh mesh) {
        if (mesh == null) {
            return "null";
        }
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            return "UNCOMPILED";
        }
        if (mesh == CompiledSectionMesh.EMPTY) {
            return "EMPTY";
        }
        return mesh.getClass().getSimpleName();
    }

    private static ViewArea minecraft$viewArea(LevelRenderer renderer) {
        try {
            return (ViewArea) F_VIEW_AREA.get(renderer);
        } catch (Exception e) {
            throw new RuntimeException("farlands: LevelRenderer.viewArea", e);
        }
    }

    private static List<?> minecraft$visibleSections(LevelRenderer renderer) {
        try {
            return (List<?>) F_VISIBLE_SECTIONS.get(renderer);
        } catch (Exception e) {
            throw new RuntimeException("farlands: LevelRenderer.visibleSections", e);
        }
    }

    private static SectionRenderDispatcher.RenderSection minecraft$renderSectionAt(ViewArea viewArea, BlockPos pos) {
        try {
            return (SectionRenderDispatcher.RenderSection) M_RENDER_SECTION_AT.invoke(viewArea, pos);
        } catch (Exception e) {
            throw new RuntimeException("farlands: ViewArea.getRenderSectionAt", e);
        }
    }

    private static Object minecraft$octreeRoot(Octree octree) {
        if (octree == null) {
            return null;
        }
        try {
            return F_OCTREE_ROOT.get(octree);
        } catch (Exception e) {
            throw new RuntimeException("farlands: Octree.root", e);
        }
    }

    private static boolean minecraft$needsFullUpdate(SectionOcclusionGraph graph) {
        try {
            return (Boolean) F_NEEDS_FULL_UPDATE.get(graph);
        } catch (Exception e) {
            throw new RuntimeException("farlands: SectionOcclusionGraph.needsFullUpdate", e);
        }
    }

    /** 全量重建任务是否已结束。未调度时无任务，返回 done。 */
    private static boolean minecraft$fullUpdateTaskDone(SectionOcclusionGraph graph) {
        try {
            Object task = F_FULL_UPDATE_TASK.get(graph);
            return task == null || ((java.util.concurrent.Future<?>) task).isDone();
        } catch (Exception e) {
            throw new RuntimeException("farlands: SectionOcclusionGraph.fullUpdateTask", e);
        }
    }

    private static boolean minecraft$needsFrustumUpdate(SectionOcclusionGraph graph) {
        try {
            return ((java.util.concurrent.atomic.AtomicBoolean) F_NEEDS_FRUSTUM_UPDATE.get(graph)).get();
        } catch (Exception e) {
            throw new RuntimeException("farlands: SectionOcclusionGraph.needsFrustumUpdate", e);
        }
    }

    // ---- 渲染侧与客户端 chunk 存储的私有成员访问 ----
    // viewArea、visibleSections、Octree.root、遮挡图的三个调度标志都是 private，
    // ViewArea.getRenderSectionAt 是 protected，ClientChunkCache.storage 与其 Storage.chunks
    // 也是 private，跨包只能反射。字段名在类加载期解析一次，失败即抛。

    private static final Field F_CLIENT_STORAGE;
    private static final Field F_STORAGE_CHUNKS;
    private static final Field F_VIEW_AREA;
    private static final Field F_VISIBLE_SECTIONS;
    private static final Field F_OCTREE_ROOT;
    private static final Field F_NEEDS_FULL_UPDATE;
    private static final Field F_FULL_UPDATE_TASK;
    private static final Field F_NEEDS_FRUSTUM_UPDATE;
    private static final Method M_RENDER_SECTION_AT;

    static {
        try {
            F_VIEW_AREA = LevelRenderer.class.getDeclaredField("viewArea");
            F_VIEW_AREA.setAccessible(true);
            F_VISIBLE_SECTIONS = LevelRenderer.class.getDeclaredField("visibleSections");
            F_VISIBLE_SECTIONS.setAccessible(true);
            F_OCTREE_ROOT = Octree.class.getDeclaredField("root");
            F_OCTREE_ROOT.setAccessible(true);
            F_NEEDS_FULL_UPDATE = SectionOcclusionGraph.class.getDeclaredField("needsFullUpdate");
            F_NEEDS_FULL_UPDATE.setAccessible(true);
            F_FULL_UPDATE_TASK = SectionOcclusionGraph.class.getDeclaredField("fullUpdateTask");
            F_FULL_UPDATE_TASK.setAccessible(true);
            F_NEEDS_FRUSTUM_UPDATE = SectionOcclusionGraph.class.getDeclaredField("needsFrustumUpdate");
            F_NEEDS_FRUSTUM_UPDATE.setAccessible(true);
            M_RENDER_SECTION_AT = ViewArea.class.getDeclaredMethod("getRenderSectionAt", BlockPos.class);
            M_RENDER_SECTION_AT.setAccessible(true);
            F_CLIENT_STORAGE = ClientChunkCache.class.getDeclaredField("storage");
            F_CLIENT_STORAGE.setAccessible(true);
            F_STORAGE_CHUNKS = F_CLIENT_STORAGE.getType().getDeclaredField("chunks");
            F_STORAGE_CHUNKS.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("fail ClientFarLandsCommands", e);
        }
    }
}
