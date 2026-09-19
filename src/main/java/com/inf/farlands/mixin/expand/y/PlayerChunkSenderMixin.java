package com.inf.farlands.mixin.expand.y;

import com.inf.farlands.util.window.WindowSendState;
import com.inf.farlands.util.window.WindowedChunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把发送时刻的玩家窗口写进 WindowSendState，取代建 chunk 时的窗口快照。
 *
 * sendChunk 是 private static，每个 chunk 调一次，PlayerChunkSender 按玩家各自持有队列；
 * HEAD 设置该玩家的窗口 minY，RETURN 清除。
 */
@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void captureWindow(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk,
            CallbackInfo ci) {
        ServerPlayer player = packetListener.player;
        int centerY = Mth.floorDiv(player.getBlockY(), 16);
        WindowSendState.setWindowMinY(centerY - ((WindowedChunk) chunk).windowHalfBelow());
    }

    @Inject(method = "sendChunk", at = @At("RETURN"))
    private static void clearWindow(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk,
            CallbackInfo ci) {
        WindowSendState.clear();
    }
}
