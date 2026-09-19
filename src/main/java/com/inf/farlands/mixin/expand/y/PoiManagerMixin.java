package com.inf.farlands.mixin.expand.y;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * POI 查找的 Y 遍历范围补上查询位置附近的 section。
 *
 * vanilla 的 getInChunk 只遍历 levelHeightAccessor 的 [getMinSectionY,
 * getMaxSectionY]。对
 * ServerLevel 这是维度高度 24 格，窗口滑到维度之外时，那些 section 的 POI 查不到——数据在、
 * storage 正常，只是不在查找范围里，表现为村民认不出工作方块与床。
 *
 * 改成"维度范围 ∪ 查询位置 ±(distance/16+1)"，两端去重。正常 Y 时查询范围被维度范围吸收，
 * 与 vanilla 等价；维度外时覆盖到，村民能查到。
 *
 * 两个成员都走反射，因为都声明在父类 SectionStorage 上，@Shadow 不解析继承来的字段：
 * levelHeightAccessor 取维度范围
 * get 取条目——它对维度外未预填充的 section 不读盘、不抛，无条目返回 null；
 * 而 getOrLoad 会走 unpackChunk 读盘，读不到再抛 IllegalStateException
 */
@Mixin(PoiManager.class)
public class PoiManagerMixin {

    private static final Field F_LEVEL_HEIGHT_ACCESSOR;
    private static final Method M_GET;

    static {
        try {
            F_LEVEL_HEIGHT_ACCESSOR = SectionStorage.class.getDeclaredField("levelHeightAccessor");
            F_LEVEL_HEIGHT_ACCESSOR.setAccessible(true);
            M_GET = SectionStorage.class.getDeclaredMethod("get", long.class);
            M_GET.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("fail PoiManagerMixin", e);
        }
    }

    private static LevelHeightAccessor farlands$heightAccessor(PoiManager self) {
        try {
            return (LevelHeightAccessor) F_LEVEL_HEIGHT_ACCESSOR.get(self);
        } catch (Exception e) {
            throw new RuntimeException("farlands: PoiManager.levelHeightAccessor", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<PoiSection> farlands$get(PoiManager self, long sectionPos) {
        try {
            return (Optional<PoiSection>) M_GET.invoke(self, sectionPos);
        } catch (Exception e) {
            throw new RuntimeException("farlands: PoiManager.get", e);
        }
    }

    @Overwrite
    public Stream<PoiRecord> getInSquare(Predicate<Holder<PoiType>> predicate, BlockPos center, int radius,
            PoiManager.Occupancy occupancy) {
        PoiManager self = (PoiManager) (Object) this;
        LevelHeightAccessor heightAccessor = farlands$heightAccessor(self);
        int chunkRadius = Math.floorDiv(radius, 16) + 1;
        int nearMin = SectionPos.blockToSectionCoord(center.getY()) - chunkRadius;
        int nearMax = SectionPos.blockToSectionCoord(center.getY()) + chunkRadius + 1;
        int dimMin = heightAccessor.getMinSectionY();
        int dimMax = heightAccessor.getMaxSectionY();
        return ChunkPos.rangeClosed(ChunkPos.containing(center), chunkRadius).flatMap(chunkPos -> IntStream
                .concat(IntStream.rangeClosed(dimMin, dimMax), IntStream.range(nearMin, nearMax))
                .distinct()
                .mapToObj(sy -> farlands$get(self, SectionPos.of(chunkPos, sy).asLong()))
                .filter(java.util.Objects::nonNull)
                .filter(Optional::isPresent)
                .flatMap(o -> o.get().getRecords(predicate, occupancy)))
                .filter(record -> {
                    BlockPos pos = record.getPos();
                    return Math.abs(pos.getX() - center.getX()) <= radius
                            && Math.abs(pos.getZ() - center.getZ()) <= radius;
                });
    }
}
