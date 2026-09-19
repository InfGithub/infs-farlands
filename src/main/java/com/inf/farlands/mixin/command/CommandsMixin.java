package com.inf.farlands.mixin.command;

import com.inf.farlands.command.FarlandsCommandRegistry;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版命令注册完成后把 mod 的命令追加进去。
 *
 * <p>
 * {@code Commands} 的构造器是原版全部命令的唯一注册点，而 {@code ReloadableServerResources}
 * 每次重建都会新建 {@code Commands}，所以注入这里就能自动覆盖数据包 reload。
 *
 * <p>
 * 构造器不能 {@code @Overwrite}，但可以 {@code @Inject}。handler 的参数必须与目标构造器
 * 逐字匹配：{@code (Commands$CommandSelection, CommandBuildContext)}，随后是
 * {@code CallbackInfo}。
 *
 * <p>
 * 注意 {@code Bootstrap.validate()} 也会构造 {@code Commands}（校验全部命令的歧义与参数类型
 * 注册情况），因此本注入在校验期同样会触发，详见 {@link FarlandsCommandRegistry} 的说明。
 */
@Mixin(Commands.class)
public class CommandsMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void farlands$registerCommands(Commands.CommandSelection commandSelection,
            CommandBuildContext context, CallbackInfo ci) {
        FarlandsCommandRegistry.fireServer(((Commands) (Object) this).getDispatcher(), commandSelection, context);
    }
}
