package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;

/** Sends the chunks nearest the watched drone first, as vanilla does around the player (DESIGN.md section 2.12). */
@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderMixin {
    @WrapOperation(method = "sendNextChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
    private ChunkPos seekerdrones$viewChunk(ServerPlayer player, Operation<ChunkPos> original) {
        return FpvFeeds.viewChunk(player, original.call(player));
    }
}
