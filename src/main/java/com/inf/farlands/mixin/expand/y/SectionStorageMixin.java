package com.inf.farlands.mixin.expand.y;

import java.util.Optional;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 极端 Y 的 section storage 条目缺失不再抛异常。
 *
 * vanilla 的 getOrLoad 假设"维度范围内 readColumn（26.1.2 改名 unpackChunk）之后必有条目"，
 * 无条目就抛 IllegalStateException。而本 port 的窗口可以滑到维度高度之外，那些 section 的
 * 数据不在磁盘预填充的范围里，读盘后仍然无条目。后果是 PoiManager.add → getOrCreate →
 * getOrLoad 抛异常，工作方块、床这类 POI 在 -64 以下放不下去。
 *
 * 改成返回 Optional.empty()：getOrCreate 收到 empty 会新建并存入 storage，POI 正常注册。
 * unpackChunk 保留，维度范围内已持久化 POI 的磁盘恢复不受影响。
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin {

    @Shadow
    protected abstract Optional<?> get(long sectionPos);

    @Shadow
    protected abstract boolean outsideStoredRange(long sectionPos);

    @Shadow
    private void unpackChunk(ChunkPos chunkPos) {
    }

    @Overwrite
    protected Optional<?> getOrLoad(long sectionPos) {
        if (this.outsideStoredRange(sectionPos)) {
            return Optional.empty();
        }
        Optional<?> loaded = this.get(sectionPos);
        if (loaded != null) {
            return loaded;
        }
        this.unpackChunk(SectionPos.of(sectionPos).chunk());
        loaded = this.get(sectionPos);
        // 窗口段在磁盘预填充范围之外，读盘后仍无条目。返回 empty，交给 getOrCreate 新建。
        return loaded == null ? Optional.empty() : loaded;
    }
}
