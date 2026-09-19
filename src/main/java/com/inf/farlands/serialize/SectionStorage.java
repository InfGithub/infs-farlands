package com.inf.farlands.serialize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * fsa 文件格式层，单文件实现。
 *
 * 布局：
 * 扇区 0-31：偏移表 131072B，32768 × 4B，槽位 idx = (yLocal<<10)|(zLocal<<5)|xLocal
 * 扇区 32：元数据，magic 4B + formatVersion 4B + 保留
 * 扇区 33 起：数据区，4KB 扇区，变长 section 条目
 *
 * 线程模型是主线程权威加无状态 IO。状态 offsets/usedSectors/dirtyPages 由主线程独占，
 * alloc、commit、读判定都即时，因此无需加锁。FileChannel 由主线程打开，IO 线程只做纯
 * file 读写 doWrite/readData/writePages/close，不碰状态。
 *
 * 两段式提交保证写失败不丢数据。prepareWrite 在主线程 alloc 出区间并返回 PendingWrite，
 * 此时不更新 offsets。doWrite 由 IO 线程写数据扇区。commitWrite 回到主线程才写 offsets、
 * 标脏页、释放旧扇区。写失败就不 commit，内存保留重试。若在 commit 前崩溃，数据扇区成为
 * 孤儿而磁盘 offsets 还是旧值，重启重建位图即可回收。
 *
 * 刷盘分两步。flushAggregate 在主线程把脏页聚合成 4KB 页 buffer，writePages 由 IO 线程
 * 逐页写。close 也由 IO 线程执行，调用前主线程必须已 flushAggregate 提交，不遗留脏页。
 *
 * 本类零改动移植，只用 JDK。区域文件格式层在 1.21.1 与 26.1.2 逐字相同，8192B 头、
 * 4B 长度加一语义、1B 版本、sectorOffset 左移 8 或 count、RegionBitmap.allocate 同一算法。
 */
public final class SectionStorage implements AutoCloseable {

    public static final int SECTOR_BYTES = 4096;
    public static final int SLOTS = 32768;
    public static final int HEADER_SECTORS = 33;
    /** 槽位位数：yLocal 左移 10 | zLocal 左移 5 | xLocal。 */
    public static final int SLOTS_PER_PAGE = 1024; // 一个 4KB 页的槽位数
    private static final int OFFSET_TABLE_BYTES = SLOTS * 4;
    private static final int OFFSET_TABLE_PAGES = OFFSET_TABLE_BYTES / SECTOR_BYTES; // 32
    private static final int MAGIC = 0x46534141; // "FSAA"
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SECTORS_PER_SECTION = 255; // offset 低 8 位

    public record PendingWrite(int idx, int sectorOffset, int sectorCount, byte[] entry) {
    }

    public record SlotRef(int idx, int sectionY, int sectorOffset, int sectorCount) {
    }

    public record PageWrite(int pageIndex, byte[] data) {
    }

    private final Path path;
    private final FileChannel file;
    private final boolean sync;

    // ---- 主线程独占状态 ----

    private final int[] offsets = new int[SLOTS];
    private final BitSet usedSectors = new BitSet();
    private final boolean[] dirtyPages = new boolean[OFFSET_TABLE_PAGES];

    private SectionStorage(Path path, FileChannel file, boolean sync) {
        this.path = path;
        this.file = file;
        this.sync = sync;
    }

    /** 主线程：懒创建并打开。magic 不符即视为损坏，抛 IOException。 */
    public static SectionStorage open(Path path, boolean sync) throws IOException {
        FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        SectionStorage storage = new SectionStorage(path, file, sync);
        storage.loadHeader();
        return storage;
    }

    private void loadHeader() throws IOException {
        long size = Files.size(path);
        if (size < (long) HEADER_SECTORS * SECTOR_BYTES) {
            initHeader();
        } else {
            ByteBuffer meta = ByteBuffer.allocate(SECTOR_BYTES);
            file.read(meta, (long) 32 * SECTOR_BYTES);
            meta.flip();
            if (meta.remaining() >= 8 && meta.getInt() != MAGIC) {
                throw new IOException("Not a fsa file: " + path);
            }
            ByteBuffer offBuf = ByteBuffer.allocate(OFFSET_TABLE_BYTES);
            file.read(offBuf, 0L);
            offBuf.flip();
            for (int i = 0; i < SLOTS; i++) {
                int v = offBuf.getInt();
                offsets[i] = v;
                if (v != 0) {
                    int off = getSectorNumber(v);
                    int cnt = getNumSectors(v);
                    usedSectors.set(off, off + cnt);
                }
            }
        }
        usedSectors.set(0, HEADER_SECTORS);
    }

    private void initHeader() throws IOException {
        ByteBuffer meta = ByteBuffer.allocate(SECTOR_BYTES);
        meta.putInt(MAGIC);
        meta.putInt(FORMAT_VERSION);
        meta.flip();
        file.write(meta, (long) 32 * SECTOR_BYTES);
        Arrays.fill(offsets, 0);
        Arrays.fill(dirtyPages, false);
    }

    /** 文件内槽位索引。xLocal 与 zLocal 是 region 内 0..31，yLocal 0..31。 */
    public static int slotIndex(int xLocal, int zLocal, int yLocal) {
        return (yLocal << 10) | (zLocal << 5) | xLocal;
    }

    private static int pack(int sectorOffset, int sectorCount) {
        return (sectorOffset << 8) | sectorCount;
    }

    private static int getSectorNumber(int packed) {
        return packed >>> 8 & 16777215;
    }

    private static int getNumSectors(int packed) {
        return packed & 0xFF;
    }

    /** 主线程：分配连续空闲扇区，取首个足够大的区间并标记占用。 */
    private int allocate(int count) {
        int i = HEADER_SECTORS;
        while (true) {
            int j = usedSectors.nextClearBit(i);
            int k = usedSectors.nextSetBit(j);
            if (k == -1 || k - j >= count) {
                usedSectors.set(j, j + count);
                return j;
            }
            i = k;
        }
    }

    // ---- 主线程：写两段式 ----

    /** 主线程：只分配扇区，不更新 offsets。超限返回 null，调用方保留内存下次重试。 */
    public PendingWrite prepareWrite(int idx, byte[] entry) {
        int need = (entry.length + SECTOR_BYTES - 1) / SECTOR_BYTES;
        if (need > MAX_SECTORS_PER_SECTION) {
            com.inf.farlands.InfsFarlands.LOGGER.error("fsa section too large: {} bytes at idx {}", entry.length, idx);
            return null;
        }
        int alloc = allocate(need);
        return new PendingWrite(idx, alloc, need, entry);
    }

    /** 主线程：写 offsets、标脏页、释放旧扇区。写失败时不要调用，让数据扇区留作孤儿回收。 */
    public void commitWrite(PendingWrite pw) {
        int old = offsets[pw.idx];
        offsets[pw.idx] = pack(pw.sectorOffset, pw.sectorCount);
        dirtyPages[pw.idx / SLOTS_PER_PAGE] = true;
        if (old != 0) {
            int oldOff = getSectorNumber(old);
            int oldCnt = getNumSectors(old);
            usedSectors.clear(oldOff, oldOff + oldCnt);
        }
    }

    public boolean hasSection(int idx) {
        return offsets[idx] != 0;
    }

    /** 主线程：单槽即时查询，不存在返回 null。 */
    public SlotRef getSlot(int idx, int sectionY) {
        int v = offsets[idx];
        return v == 0 ? null : new SlotRef(idx, sectionY, getSectorNumber(v), getNumSectors(v));
    }

    /** 主线程：把脏页聚合成 4KB 页 buffer 并清脏标记。返回空表示无脏页。 */
    public List<PageWrite> flushAggregate() {
        List<PageWrite> out = new ArrayList<>();
        for (int p = 0; p < OFFSET_TABLE_PAGES; p++) {
            if (dirtyPages[p]) {
                ByteBuffer buf = ByteBuffer.allocate(SECTOR_BYTES);
                int base = p * SLOTS_PER_PAGE;
                for (int i = 0; i < SLOTS_PER_PAGE; i++) {
                    buf.putInt(offsets[base + i]);
                }
                out.add(new PageWrite(p, buf.array()));
                dirtyPages[p] = false;
            }
        }
        return out;
    }

    // ---- IO 线程：无状态读写 ----

    /**
     * IO 线程：写数据扇区，连续区间合并成一次写，不碰 offsets 与 usedSectors。
     *
     * 每条目必须 pad 到自身扇区组边界，即 sectorCount 乘 4096 的整数倍。读端 readData 按
     * sectorOffset 乘 4096 随机读、decode 从头解析长度头，若紧凑拼接就会让后续条目错位，
     * 表现为 offset 指向的扇区没有数据、重进 decode 失败、section 丢失。
     */
    public void doWrite(List<PendingWrite> batch) throws IOException {
        List<PendingWrite> sorted = new ArrayList<>(batch);
        sorted.sort(Comparator.comparingInt(PendingWrite::sectorOffset));
        int i = 0;
        while (i < sorted.size()) {
            PendingWrite pw = sorted.get(i);
            int start = pw.sectorOffset;
            int end = pw.sectorOffset + pw.sectorCount;
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).sectorOffset == end) {
                end += sorted.get(j).sectorCount;
                j++;
            }
            int total = 0;
            for (int k = i; k < j; k++) {
                total += sorted.get(k).sectorCount * SECTOR_BYTES;
            }
            ByteBuffer buf = ByteBuffer.allocate(total);
            for (int k = i; k < j; k++) {
                PendingWrite e = sorted.get(k);
                buf.put(e.entry);
                int pad = e.sectorCount * SECTOR_BYTES - e.entry.length;
                while (pad-- > 0) {
                    buf.put((byte) 0);
                }
            }
            buf.flip();
            // FileChannel.write 不保证一次写满，循环到 buf 空为止
            long written = 0;
            while (buf.hasRemaining()) {
                written += file.write(buf, (long) start * SECTOR_BYTES + written);
            }
            i = j;
        }
    }

    /** IO 线程：读一个 section 条目。不存在或损坏返回 null，由调用方判定。 */
    public byte[] readData(int sectorOffset, int sectorCount) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(sectorCount * SECTOR_BYTES);
        file.read(buf, (long) sectorOffset * SECTOR_BYTES);
        buf.flip();
        if (buf.remaining() < 5) {
            return null;
        }
        byte[] entry = new byte[buf.remaining()];
        buf.get(entry);
        return entry;
    }

    /** IO 线程：逐页写偏移表，每页一次独立 4KB 写，因此页级原子。 */
    public void writePages(List<PageWrite> pages) throws IOException {
        for (PageWrite pw : pages) {
            file.write(ByteBuffer.wrap(pw.data()), (long) pw.pageIndex() * SECTOR_BYTES);
        }
        if (sync) {
            file.force(false);
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    public Path path() {
        return path;
    }
}
