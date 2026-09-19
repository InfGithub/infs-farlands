package com.inf.farlands.mixin.expand.y;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.entity.BlockEntityType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 重建方块实体位置时改用完整的世界 Y。
 *
 * vanilla 的 getBlockEntitiesTags 用 BlockEntityInfo.packedXZ 与那个被 short 截断过的 y
 * 拼出方块实体坐标，|Y| 超过 32767 时位置就错。完整 Y 由
 * {@code ClientboundLevelChunkPacketData$BlockEntityInfoMixin} 注入的字段携带。
 *
 * BlockEntityInfo 是私有静态嵌套类，源码里连类型都引用不到，所以列表字段与它上面每个字段
 * 一律走反射，方法体里用 Object 迭代。那个注入字段在混入后带前缀，按后缀 yCorrect 查找。
 *
 * 与同包的 ClientboundLevelChunkPacketDataMixin 分开：那个只管窗口发包的两个方法。
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataBlockEntityMixin {

    private static final Class<?> C_PACKET;
    private static final Class<?> C_BLOCK_ENTITY_INFO;
    private static final Field F_BLOCK_ENTITIES_DATA;
    private static final Field F_PACKED_XZ;
    private static final Field F_TYPE;
    private static final Field F_TAG;
    private static final Field F_Y_CORRECT;

    static {
        try {
            C_PACKET = ClientboundLevelChunkPacketData.class;
            C_BLOCK_ENTITY_INFO = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo");
            F_BLOCK_ENTITIES_DATA = accessible(C_PACKET.getDeclaredField("blockEntitiesData"));
            F_PACKED_XZ = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("packedXZ"));
            F_TYPE = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("type"));
            F_TAG = accessible(C_BLOCK_ENTITY_INFO.getDeclaredField("tag"));
            F_Y_CORRECT = findYCorrect();
        } catch (Exception e) {
            throw new RuntimeException("fail ClientboundLevelChunkPacketDataBlockEntityMixin", e);
        }
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    /** 混入给 @Unique 字段加前缀，所以按后缀匹配。 */
    private static Field findYCorrect() throws NoSuchFieldException {
        for (Field f : C_BLOCK_ENTITY_INFO.getDeclaredFields()) {
            if (f.getName().endsWith("yCorrect")) {
                return accessible(f);
            }
        }
        throw new NoSuchFieldException("yCorrect not found on BlockEntityInfo");
    }

    @SuppressWarnings("unchecked")
    @Overwrite
    private void getBlockEntitiesTags(ClientboundLevelChunkPacketData.BlockEntityTagOutput output, int chunkX,
            int chunkZ) {
        int baseX = 16 * chunkX;
        int baseZ = 16 * chunkZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            for (Object info : (List<Object>) F_BLOCK_ENTITIES_DATA.get(this)) {
                int packedXZ = F_PACKED_XZ.getInt(info);
                int y = F_Y_CORRECT.getInt(info);
                int x = baseX + SectionPos.sectionRelative(packedXZ >> 4);
                int z = baseZ + SectionPos.sectionRelative(packedXZ);
                pos.set(x, y, z);
                output.accept(pos, (BlockEntityType<?>) F_TYPE.get(info), (CompoundTag) F_TAG.get(info));
            }
        } catch (Exception e) {
            throw new RuntimeException("farlands: getBlockEntitiesTags", e);
        }
    }
}
