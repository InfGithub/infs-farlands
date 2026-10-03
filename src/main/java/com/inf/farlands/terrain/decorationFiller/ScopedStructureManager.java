package com.inf.farlands.terrain.decorationFiller;

import java.lang.reflect.Field;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.StructureCheck;

/**
 * 以自研 region 为 level 的 StructureManager。
 *
 * <p>为什么必须换：装饰那一步的结构段会走 StructureManager.startsForStructure，而它取数用的是
 * 管理器自己的 level。沿用 level 那一份，池线程上就落到 ServerChunkCache 的阻塞取数，把活踢回
 * 主线程并等它；主线程此时可能在等生成收尾，两边互为条件。换成以 region 为 level 之后，取数落到
 * region 的非阻塞句柄上。
 *
 * <p>构造件只能反射取：StructureManager 对外只有 forWorldGenRegion，而它只收 vanilla 的
 * WorldGenRegion，自研 region 不是那个类型。仓库里已有同型先例。
 */
public final class ScopedStructureManager {

    private static final Field F_WORLD_OPTIONS;
    private static final Field F_STRUCTURE_CHECK;

    static {
        try {
            F_WORLD_OPTIONS = StructureManager.class.getDeclaredField("worldOptions");
            F_WORLD_OPTIONS.setAccessible(true);
            F_STRUCTURE_CHECK = StructureManager.class.getDeclaredField("structureCheck");
            F_STRUCTURE_CHECK.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: reflection on StructureManager failed", e);
        }
    }

    private ScopedStructureManager() {
    }

    /** 以该 region 为 level 的管理器；两个构造件取自 level 那一份。装饰相与结构相共用。 */
    public static StructureManager of(ServerLevel level, DecorationRegion region) {
        try {
            StructureManager source = level.structureManager();
            return new StructureManager(region, (WorldOptions) F_WORLD_OPTIONS.get(source),
                    (StructureCheck) F_STRUCTURE_CHECK.get(source));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("farlands: failed to scope StructureManager to the decoration region", e);
        }
    }
}
