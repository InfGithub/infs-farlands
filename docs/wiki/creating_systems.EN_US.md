# Creating Systems

Systems come in four families:

| Family  | Interface                     | Required method                    |
| ------- | ----------------------------- | ---------------------------------- |
| Biome   | `BiomeSystem`                 | `fillBiomes`                       |
| Terrain | `NoiseSystem` / `BlockSystem` | `createFinalDensity` / `fillBlock` |
| Surface | `SurfaceSystem`               | `applySurface`                     |
| Carver  | `CarverSystem`                | `applyCarvers`                     |

## Contract

- The class must be public and have exactly one public constructor taking `SystemArgs`.

## Notes

- `NoiseSystem.createFinalDensity` must return a new instance on every call.
- `TerrainSystem.createFluidPicker` and `createAquifer` return `null` by default.
- `NoiseSystem.createRouter` replaces only `finalDensity` by default.
- `NoiseSystem.noiseSize` returning `null` means the default grid is used.
- Cells where `BlockSystem.fillBlock` returns `null` fall back to the density chain.

## Defining an Example System

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

## Declaring Parameters

Parameters are declared on a `static final SystemParams` field annotated with `@SystemDefaultParams`.

Each parameter is declared with `SystemParamSpec`:

```java
@SystemDefaultParams
public static final SystemParams DEFAULT_PARAMS = SystemParams.of(
        SystemParamSpec.ofInt("size").define(4).build(),
        SystemParamSpec.ofDouble("scaleX").define(1.0).build());
```

A parameter can also declare keywords:

```java
SystemParamSpec.ofLong("seed")
        .keyword("auto", () -> SystemsData.Arg.ofLong(ThreadLocalRandom.current().nextLong()),
                "vmod.param.seed.random")
        .build()
```

When no `@SystemDefaultParams` field is declared, the parameter box for that system degrades to a single free-form text box holding an SNBT map.

## Registering

Registration goes through the methods on `FarlandsSystems`:

```java
SystemId id = new SystemId(Identifier.fromNamespaceAndPath("vmod", "example_noise_system"));
FarlandsSystems.registerTerrain(id, ExampleNoiseSystem.class);
```

The `id` must not duplicate an existing registration, and the namespace is unrestricted.
