package com.inf.farlands.terrain.registry;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.inf.farlands.terrain.BiomeSystem;
import com.inf.farlands.terrain.CarverSystem;
import com.inf.farlands.terrain.SurfaceSystem;
import com.inf.farlands.terrain.TerrainSystem;
import com.inf.farlands.terrain.registry.SystemsData.Arg;
import com.inf.farlands.terrain.registry.SystemsData.LevelSelection;
import com.inf.farlands.terrain.registry.SystemsData.SystemSelection;
import com.mojang.serialization.Dynamic;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 系统配置的读写与落地。
 *
 * <p>
 * 读自己来，不走 SavedDataStorage：它的读路径把解析失败记成日志后返回 null，上层
 * computeIfAbsent 随即新建默认对象，损坏的配置因此不会失败，而这条路要的是一律抛出。
 *
 * <p>
 * 写借 SavedDataStorage：set 只标脏进缓存，由 MinecraftServer.saveAllChunks 按既有的保存节奏
 * 落盘，编码用同一份 Codec，两端格式不会走偏。配置文件在磁盘上不存在而世界又已初始化时即抛；
 * 只有新世界的首次解析才写默认值。
 */
public final class SystemsIO {

    private SystemsIO() {
    }

    /** 配置文件路径，与 SavedDataStorage 的取法同式。 */
    public static Path file(MinecraftServer server) {
        return SystemsData.ID.withSuffix(".dat")
                .resolveAgainst(server.getWorldPath(LevelResource.DATA));
    }

    /**
     * 创建世界页签的选择在 level 构造之前的暂存。客户端在服务端线程启动之前放进来，本类在文件
     * 不存在且世界尚未初始化的那条分支里取用一次即清空，所以既成世界与专服都碰不到它。
     *
     * <p>只在「这次 level 构造」的生命周期内有效，不进磁盘、不做回退。取用时落盘仍由
     * {@link net.minecraft.world.level.storage.SavedDataStorage} 负责，本类不写文件。
     */
    private static volatile SystemsData staged;

    /** level 构造前把界面选择交给本类。由集成服务器在 createLevels 之前调用。 */
    public static void stageForLevelCreation(SystemsData data) {
        staged = data;
    }

    private static SystemsData takeStaged() {
        SystemsData data = staged;
        staged = null;
        return data;
    }

    /** 解析该世界的配置：文件在则严格自读，不在则按新世界落默认并写回，其余情形抛。 */
    public static SystemsData resolve(MinecraftServer server) {
        Path file = file(server);
        if (Files.exists(file)) {
            return read(file);
        }
        if (server.getWorldData().overworldData().isInitialized()) {
            throw new IllegalStateException("farlands: 已存在的世界没有系统配置文件 " + file);
        }
        SystemsData data = takeStaged();
        if (data == null) {
            data = defaultFor(server);
        }
        server.getDataStorage().set(SystemsData.TYPE, data);
        return data;
    }

    /** 严格自读。任何异常都带上文件路径直穿，不做回退也不新建默认。 */
    public static SystemsData read(Path file) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.defaultQuota());
        } catch (Exception e) {
            throw new IllegalStateException("farlands: 读取系统配置失败 " + file, e);
        }
        Tag data = root.get("data");
        if (data == null) {
            throw new IllegalStateException("farlands: 系统配置缺少 data 键 " + file);
        }
        try {
            return SystemsData.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow(IllegalStateException::new);
        } catch (RuntimeException e) {
            throw new IllegalStateException("farlands: 系统配置解析失败 " + file, e);
        }
    }

    /** 该维度的选择，缺失即抛。 */
    public static LevelSelection selection(SystemsData data, Identifier dimension) {
        LevelSelection selection = data.selection(dimension);
        if (selection == null) {
            throw new IllegalStateException("farlands: 系统配置缺少维度 " + dimension);
        }
        return selection;
    }

    /**
     * 内置默认：地形用 vanilla 噪声系统且 seed 为 0，其余三族走各自的 VOID。维度取注册表里的全部
     * LevelStem，新世界因此不会因为数据包加了维度而缺条目。只在该世界还没有配置文件时生效，已有配置
     * 文件的世界一律以文件为准。
     */
    public static SystemsData defaultFor(MinecraftServer server) {
        Map<Identifier, LevelSelection> levels = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM).entrySet()) {
            levels.put(entry.getKey().identifier(), defaultSelection());
        }
        return new SystemsData(levels);
    }

    private static LevelSelection defaultSelection() {
        return new LevelSelection(
                new SystemSelection(SystemRegistries.TERRAIN_OVERWORLD_VANILLA_NOISE_SYSTEM.value(),
                        Map.of("seed", Arg.ofLong(0L))),
                new SystemSelection(SystemRegistries.BIOME_MISC_VOID_BIOME_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.SURFACE_MISC_VOID_SURFACE_SYSTEM.value(), Map.of()),
                new SystemSelection(SystemRegistries.CARVER_MISC_VOID_CARVER_SYSTEM.value(), Map.of()));
    }

    public static TerrainSystem terrain(LevelSelection selection) {
        return SystemRegistries.terrain(systemId(selection.terrain()), args(selection.terrain()));
    }

    public static BiomeSystem biome(LevelSelection selection) {
        return SystemRegistries.biome(systemId(selection.biome()), args(selection.biome()));
    }

    public static SurfaceSystem surface(LevelSelection selection) {
        return SystemRegistries.surface(systemId(selection.surface()), args(selection.surface()));
    }

    public static CarverSystem carver(LevelSelection selection) {
        return SystemRegistries.carver(systemId(selection.carver()), args(selection.carver()));
    }

    private static SystemId systemId(SystemSelection selection) {
        return new SystemId(selection.id());
    }

    /** 缺参数由 SystemArgs 的取值方抛，类型与越界在这里抛。 */
    private static SystemArgs args(SystemSelection selection) {
        SystemArgs args = new SystemArgs();
        for (Map.Entry<String, Arg> entry : selection.args().entrySet()) {
            set(args, entry.getKey(), entry.getValue());
        }
        return args;
    }

    private static void set(SystemArgs args, String key, Arg arg) {
        switch (arg.type()) {
            case INT -> args.setInt(key, (int) range(key, arg, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case LONG -> args.setLong(key, exactLong(key, arg));
            case SHORT -> args.setShort(key, (short) range(key, arg, Short.MIN_VALUE, Short.MAX_VALUE));
            case BYTE -> args.setByte(key, (byte) range(key, arg, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case FLOAT -> args.setFloat(key, (float) floatValue(key, arg));
            case DOUBLE -> args.setDouble(key, doubleValue(key, arg));
            case BOOLEAN -> args.setBoolean(key, number(key, arg).longValue() != 0L);
            case STRING -> args.setString(key, string(key, arg));
            case ENUM -> setEnum(args, key, arg);
        }
    }

    private static long range(String key, Arg arg, long min, long max) {
        long value = number(key, arg).longValue();
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "farlands: 系统参数 " + key + " 超出 " + arg.type().id() + " 范围: " + value);
        }
        return value;
    }

    private static long exactLong(String key, Arg arg) {
        Number number = number(key, arg);
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("farlands: 系统参数 " + key + " 不是 long 范围内的整数: " + number, e);
        }
    }

    private static double doubleValue(String key, Arg arg) {
        double value = number(key, arg).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalStateException("farlands: 系统参数 " + key + " 不是有限 double: " + value);
        }
        return value;
    }

    private static double floatValue(String key, Arg arg) {
        double value = number(key, arg).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < -Float.MAX_VALUE
                || value > Float.MAX_VALUE) {
            throw new IllegalStateException("farlands: 系统参数 " + key + " 超出 float 范围: " + value);
        }
        return value;
    }

    private static Number number(String key, Arg arg) {
        return number(key, arg.value());
    }

    private static <T> Number number(String key, Dynamic<T> value) {
        return value.getOps().getNumberValue(value.getValue()).getOrThrow(
                message -> new IllegalStateException("farlands: 系统参数 " + key + " 不是数值: " + message));
    }

    private static String string(String key, Arg arg) {
        return string(key, arg.value());
    }

    private static <T> String string(String key, Dynamic<T> value) {
        return value.getOps().getStringValue(value.getValue()).getOrThrow(
                message -> new IllegalStateException("farlands: 系统参数 " + key + " 不是字符串: " + message));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void setEnum(SystemArgs args, String key, Arg arg) {
        String className = arg.enumClass().orElseThrow(() -> new IllegalStateException(
                "farlands: 系统参数 " + key + " 声明为 enum 但缺 enumClass"));
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("farlands: 系统参数 " + key + " 的枚举类未找到: " + className, e);
        }
        if (!type.isEnum()) {
            throw new IllegalStateException("farlands: 系统参数 " + key + " 的类不是枚举: " + className);
        }
        String name = string(key, arg);
        try {
            args.setEnum(key, (Enum) Enum.valueOf((Class) type, name));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "farlands: 系统参数 " + key + " 的枚举常量不存在: " + className + "." + name, e);
        }
    }
}
