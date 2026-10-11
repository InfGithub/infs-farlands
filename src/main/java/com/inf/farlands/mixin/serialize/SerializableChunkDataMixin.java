package com.inf.farlands.mixin.serialize;

import com.inf.farlands.serialize.ChunkReadiness;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.lighting.LayerLightEventListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fsa 退役 vanilla 的 section 与光照持久化，chunk NBT 不再含方块与光照数据，全权交 fsa。
 *
 * 26.1.2 的落点变了。1.21.1 的目标是 ChunkSerializer.write(ServerLevel, ChunkAccess)，
 * 由 ChunkMap 第 790 行直接调用，旧 mixin 在那里 redirect 三处。26.1.2 的 ChunkSerializer
 * 已被 SerializableChunkData 取代，写入链路变成 ChunkMap 第 760 行调用
 * SerializableChunkData.copyOf(ServerLevel, ChunkAccess) 产出不可变快照，再 write() 把快照
 * 编码成 NBT。write() 只遍历 record 字段 sectionData，不碰活对象，所以必须在 copyOf 上切，
 * 而不是 write。
 *
 * 两处 redirect 对应旧 mixin 的前两处：
 *   一，chunk.getSections() 返回空数组。copyOf 里 hasSection 恒 false，于是既不写
 *   block_states 与 biomes，也跳过 chunkSections[i].copy()。旧 mixin 正是靠这一点顺手省掉
 *   每 section 一次深拷贝。
 *   二，LayerLightEventListener.getDataLayerData 返回 null，于是不写 BlockLight 与 SkyLight。
 *   一处 redirect 覆盖 copyOf 里 BLOCK 与 SKY 两个调用点。
 * 结果是 sectionData 为空，write() 产出空 sections ListTag，chunk NBT 只剩高度图、方块实体、
 * 实体、结构、tick 等元数据。
 *
 * 旧 mixin 的第三处处理 attachments，在 26.1.2 没有对应物。vanilla 的
 * SerializableChunkData.write() 根本不序列化 attachment，已逐行读过。而且运行时 jar 里
 * net/fabricmc 条目数为 0，ChunkAccess 没有 Fabric attachment API。旧设计里 attachment
 * 承载的 stage 在本 port 已由 SectionStage 自有承载。
 *
 * 读盘侧：没有 sections 时 parse 得到空 sectionData，read() 建出全 null 的
 * LevelChunkSection 数组，LevelChunk 构造会走 replaceMissingSections，本 port 的
 * ChunkAccessMixin 已 @Overwrite 该方法并用 containerFactory 建占位 section，因此不会 NPE。
 * 真实数据由 fsa 的读回路径灌回。
 *
 * 方块不在 NBT 里这件事另有两处后果，见本类尾部两个注入点：落盘时方块实体标签必须一律写成
 * packed 形态，载入时所在段尚未就位的标签不能拿去建实例。
 *
 * 写入链路的唯一生产点：26.1.2 全树只有 ChunkMap 第 760 行一处调用
 * SerializableChunkData.copyOf。
 */
@Mixin(SerializableChunkData.class)
public class SerializableChunkDataMixin {

    /** sections 空转，不写 block_states 与 biomes，并省掉 section 深拷贝。 */
    @Redirect(method = "copyOf", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] farlands$emptySections(ChunkAccess chunk) {
        return new LevelChunkSection[0];
    }

    /** 光照空转，不写 BlockLight 与 SkyLight。一处覆盖 BLOCK 与 SKY 两个调用点。 */
    @Redirect(method = "copyOf", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LayerLightEventListener;getDataLayerData(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/world/level/chunk/DataLayer;"))
    private static DataLayer farlands$noSectionLight(LayerLightEventListener listener, SectionPos pos) {
        return null;
    }

    /**
     * 方块实体标签一律写成 packed 形态。
     *
     * <p>
     * vanilla 的约定是「非 packed 标签蕴含方块态就在同一份 NBT 里」：{@code LevelChunk.getBlockEntityNbtForSaving}
     * 给活着的方块实体写 {@code keepPacked=false}，给还没建的写 {@code true}。本 port 的 sections 在 fsa 里，
     * NBT 没有方块，照活实体写 false 就成了假陈述：载入时 {@code SerializableChunkData} 会拿空气态去建实例，
     * 抛 Invalid block entity 之后由 {@code BlockEntity.loadStatic} 吞掉，这个方块实体就没了。
     *
     * <p>
     * 置 true 之后标签进 pending，由 {@code LevelChunk.promotePendingBlockEntity} 在方块到位时再建，
     * 载入顺序与方块位置解耦：窗口内的由 ticking 准备那一轮晋升，窗口外的留给读回收尾。
     */
    @Redirect(method = "copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag farlands$packBlockEntity(ChunkAccess chunk, BlockPos pos,
            HolderLookup.Provider registries) {
        CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos, registries);
        if (tag != null) {
            tag.putBoolean("keepPacked", true);
        }
        return tag;
    }

    /**
     * 载入时所在段尚未就位的标签不进建实例那一支。
     *
     * <p>
     * 读盘那一路的标签只活在 {@code postLoadChunk} 生成的处理器闭包里，跑 {@code runPostLoad} 之前看不到它，
     * 所以已经落盘成非 packed 的旧标签只能在被消费的那一刻拦。判据是段级就绪：该段没到 LIGHTED 或仍在读回时，
     * 把标签交给 {@code setBlockEntityNbt} 停进 pending 表并返回 null，不打那条 ERROR；段已就绪就放行原方法，
     * 让真正错配的方块按 vanilla 语义报错。
     */
    @WrapOperation(method = "lambda$postLoadChunk$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadStatic(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private static BlockEntity farlands$parkUnready(BlockPos pos, BlockState state, CompoundTag tag,
            HolderLookup.Provider registries, Operation<BlockEntity> original,
            @Local(argsOnly = true, index = 3) LevelChunk levelChunk) {
        if (levelChunk.getLevel() instanceof ServerLevel level
                && !ChunkReadiness.isReady(level, levelChunk.getPos(), pos.getY() >> 4)) {
            levelChunk.setBlockEntityNbt(tag);
            return null;
        }
        return original.call(pos, state, tag, registries);
    }
}
