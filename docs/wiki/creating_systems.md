# 创建 Systems

Systems 分四族：

| 族   | 接口                          | 必要方法                           |
| ---- | ----------------------------- | ---------------------------------- |
| 群系 | `BiomeSystem`                 | `fillBiomes`                       |
| 地形 | `NoiseSystem` / `BlockSystem` | `createFinalDensity` / `fillBlock` |
| 地表 | `SurfaceSystem`               | `applySurface`                     |
| 雕刻 | `CarverSystem`                | `applyCarvers`                     |

## 约定

- 类必须是 public，且有且仅有一个 public 构造器，形参为 `SystemArgs`。

## 注意

- `NoiseSystem` 的 `createFinalDensity` 每次调用都返回新实例。
- `TerrainSystem` 的 `createFluidPicker` 与 `createAquifer` 默认返回 `null`。
- `NoiseSystem` 的 `createRouter` 默认只替换 `finalDensity`。
- `NoiseSystem` 的 `noiseSize` 返回 `null` 表示使用默认网格。
- `BlockSystem` 的 `fillBlock` 返回 `null` 的格会重回密度链。

## 定义一个示例 System

```java
import com.inf.farlands.terrain.NoiseSystem;
import com.inf.farlands.terrain.registry.*;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

public final class ExampleNoiseSystem implements NoiseSystem {

    @SystemDefaultParams
    public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
            SystemParamSpec.ofLong("seed").define(0L).build());

    private final long seed;

    public ExampleNoiseSystem(SystemArgs args) {
        this.seed = args.getLong("seed");
    }

    @Override
    public DensityFunction createFinalDensity(NoiseRouter router) {
        return router.finalDensity();
    }
}
```

## 声明参数

参数声明在标注 `@SystemDefaultParams` 的 `static final SystemParams` 字段上。

每个参数用 `SystemParamSpec` 声明：

```java
@SystemDefaultParams
public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
        SystemParamSpec.ofInt("size").define(4).build(),
        SystemParamSpec.ofDouble("scaleX").define(1.0).build());
```

也可以给参数声明关键字：

```java
SystemParamSpec.ofLong("seed")
        .keyword("auto", () -> SystemsData.Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                "vmod.param.seed.random")
        .build()
```

未声明 `@SystemDefaultParams` 字段时，该 system 的参数框退化成一个自由文本框，内容是 SNBT 映射。

## 注册

注册入口是 `FarlandsSystems` 的方法：

```java
SystemId id = new SystemId(Identifier.fromNamespaceAndPath("vmod", "example_noise_system"));
FarlandsSystems.registerTerrain(id, ExampleNoiseSystem.class);
```

`id` 不得与既有注册重复，命名空间不限。
