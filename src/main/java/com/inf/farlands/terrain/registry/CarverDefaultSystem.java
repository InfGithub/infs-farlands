package com.inf.farlands.terrain.registry;

/**
 * 雕刻系统在各维度页上的默认选中项。
 *
 * <p>
 * 主世界取为它设计的那套，下界与末地暂无专属系统，退回 misc 那套。加了 nether 或 end 的实现后
 * 只需往对应分支填。
 */
public final class CarverDefaultSystem {

    private CarverDefaultSystem() {
    }

    public static SystemId defaultFor(FamilyPage page) {
        return switch (page) {
            case OVERWORLD -> SystemRegistries.CARVER_OVERWORLD_VANILLA_CARVER_SYSTEM;
            case THE_NETHER, THE_END -> SystemRegistries.CARVER_MISC_VOID_CARVER_SYSTEM;
        };
    }
}
