package com.inf.farlands.serialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Optional;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * section 级编解码。fsa 条目字节与 LevelChunkSection、光照、stage 互转。
 *
 * 条目格式与 Anvil chunk 条目同构。长度用 int 加一语义，用来区分存在但空与不存在，
 * fsa 内部 offset=0 已经表示不存在，这里保留一致性。随后是压缩版本 byte，取值 lz4，
 * 再后面是 lz4 压缩的 NBT。
 *
 * NBT 结构：DataVersion、Y 即绝对 sectionY 不截断、block_states、biomes、
 * BlockLight 为 byte[2048] 且 isEmpty 时跳过、SkyLight 同上、stage。
 *
 * 不接 vanilla datafix，mod 锁定 26.1.2，不跨版本。DataVersion 只作诊断与未来迁移用。
 *
 * 26.1.2 相对 1.21.1 的差异，均已核实。1.21.1 靠反射
 * ChunkSerializer.BLOCK_STATE_CODEC 与
 * ChunkSerializer.makeBiomeCodec(Registry)，而
 * 26.1.2 这两个目标随 ChunkSerializer 一起消失，该类被 SerializableChunkData 取代，
 * codec 改由 PalettedContainerFactory 的 blockStatesContainerCodec 与
 * biomeContainerCodec
 * 提供，与本 mod 的 WindowedChunk.containerFactory 同源。codec 的构造签名也变了，
 * IdMap 移进了 Strategy，但本类只用 factory 暴露的成品 codec，不受影响。
 * CompoundTag.getByteArray 现在返回 Optional，getCompound 也返回 Optional，getInt 改名为
 * getIntOr，而 contains(String,int) 这个重载已被移除，只剩 contains(String)。
 * DataVersion 取 SharedConstants.getCurrentVersion().dataVersion().version()。
 */
public final class SectionSerializer {

    private SectionSerializer() {
    }

    /** 编码：section 加两个光照层加 stage，产出含长度头的 fsa 条目字节。 */
    public static byte[] encode(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight,
            int stage, PalettedContainerFactory containerFactory, int sectionY) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        tag.putInt("Y", sectionY);
        tag.put("block_states",
                containerFactory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, section.getStates())
                        .getOrThrow());
        tag.put("biomes",
                containerFactory.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, section.getBiomes())
                        .getOrThrow());
        if (blockLight != null && !blockLight.isEmpty()) {
            tag.putByteArray("BlockLight", blockLight.getData());
        }
        if (skyLight != null && !skyLight.isEmpty()) {
            tag.putByteArray("SkyLight", skyLight.getData());
        }
        tag.putInt("stage", stage);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            LZ4BlockOutputStream lz4 = new LZ4BlockOutputStream(baos);
            DataOutputStream out = new DataOutputStream(lz4);
            NbtIo.write(tag, out);
            out.flush();
            lz4.close();
            byte[] payload = baos.toByteArray();

            ByteArrayOutputStream entry = new ByteArrayOutputStream(payload.length + 5);
            DataOutputStream entryOut = new DataOutputStream(entry);
            entryOut.writeInt(payload.length + 1); // 加一语义，vanilla 惯例
            entryOut.writeByte(4); // lz4 版本 ID，对齐 RegionFileVersion.VERSION_LZ4
            entryOut.write(payload);
            entryOut.flush();
            return entry.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("fsa encode sectionY=" + sectionY, e);
        }
    }

    public record DecodedSection(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight, int stage) {
    }

    /** 解码。数据损坏就抛异常，由调用方按不存在处理，走重生成。 */
    @SuppressWarnings("deprecation")
    public static DecodedSection decode(byte[] entry, PalettedContainerFactory containerFactory, int sectionY) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(entry));
            int len = in.readInt();
            in.readByte(); // 版本，lz4，不校验，格式版本由文件级 magic 保证
            int payloadLen = len - 1;
            if (payloadLen < 0 || payloadLen > entry.length - 5) {
                throw new IllegalStateException("fsa entry length corrupt: " + len);
            }
            byte[] payload = new byte[payloadLen];
            in.readFully(payload);

            LZ4BlockInputStream lz4 = new LZ4BlockInputStream(new ByteArrayInputStream(payload));
            DataInputStream nbtIn = new DataInputStream(lz4);
            CompoundTag tag = NbtIo.read(nbtIn);
            nbtIn.close();

            Optional<CompoundTag> blockStates = tag.getCompound("block_states");
            Optional<CompoundTag> biomes = tag.getCompound("biomes");
            if (blockStates.isEmpty() || biomes.isEmpty()) {
                throw new IllegalStateException("fsa entry missing block_states/biomes");
            }
            PalettedContainer<BlockState> states = containerFactory.blockStatesContainerCodec()
                    .parse(NbtOps.INSTANCE, blockStates.get())
                    .getOrThrow();
            PalettedContainerRO<Holder<Biome>> bios = containerFactory.biomeContainerCodec()
                    .parse(NbtOps.INSTANCE, biomes.get())
                    .getOrThrow();
            LevelChunkSection section = new LevelChunkSection(states, bios);
            DataLayer bl = tag.getByteArray("BlockLight").map(DataLayer::new).orElse(null);
            DataLayer sl = tag.getByteArray("SkyLight").map(DataLayer::new).orElse(null);
            int stage = tag.getIntOr("stage", SectionStage.UNPROCESSED);
            return new DecodedSection(section, bl, sl, stage);
        } catch (Exception e) {
            throw new RuntimeException("fsa decode sectionY=" + sectionY, e);
        }
    }
}
