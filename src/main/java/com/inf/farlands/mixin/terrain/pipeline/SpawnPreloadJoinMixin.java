package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.terrain.pipeline.SpawnPreload;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 首个玩家入场时释放出生区预加载票。
 *
 * <p>票留在预加载完成后不移除，是为了让这些 chunk 在玩家到达前不被卸载；入场时视距票已经能接管，
 * 所以这里一次性移除，幂等，之后按需卸载落盘。上一版本用 NeoForge 的登录事件，本 port 没有该事件，
 * 落点是 PlayerList.placeNewPlayer 的 RETURN。
 */
@Mixin(PlayerList.class)
public abstract class SpawnPreloadJoinMixin {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void farlands$releasePreloadTickets(Connection connection, ServerPlayer player,
            CommonListenerCookie cookie, CallbackInfo ci) {
        if (player.level() instanceof ServerLevel serverLevel) {
            SpawnPreload.release(serverLevel);
        }
    }
}
