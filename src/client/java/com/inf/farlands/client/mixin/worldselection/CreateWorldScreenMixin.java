package com.inf.farlands.client.mixin.worldselection;

import java.util.Arrays;

import com.inf.farlands.client.gui.FarlandsTab;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 给创建世界页追加一个页签。
 *
 * <p>
 * init 里的页签数组是调用点现场构造后传给 TabNavigationBar$Builder.addTabs 的，把那个数组换成
 * 长度加一的即可，不必读 tabNavigationBar 字段，也不必覆写 init。新页追加在末尾，所以
 * selectTab(0, false) 选中的仍是游戏页，原有三页的序号不变。
 *
 * <p>
 * target 串按 javap 打 clientonly jar 的描述符逐字抄写：本仓库无 refmap，注解里的 target 编译期
 * 不校验，写错只会在打开本页时抛 MixinTransformerError。
 */
@Mixin(CreateWorldScreen.class)
public class CreateWorldScreenMixin {

    @ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/TabNavigationBar$Builder;"), index = 0)
    private Tab[] farlands$appendTab(Tab[] tabs) {
        Tab[] extended = Arrays.copyOf(tabs, tabs.length + 1);
        extended[tabs.length] = new FarlandsTab();
        return extended;
    }
}
