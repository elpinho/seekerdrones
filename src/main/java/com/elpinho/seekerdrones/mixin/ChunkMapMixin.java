package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * While a player watches an FPV feed, their chunk tracking centers on the drone, within {@code camera.viewDistance}
 * (DESIGN.md section 2.12). The tracking only decides which chunks are sent: tickets, and so chunk loading, stay with
 * the player's body.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
    @WrapOperation(method = "updateChunkTracking", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
    private ChunkPos seekerdrones$viewChunk(ServerPlayer player, Operation<ChunkPos> original) {
        return FpvFeeds.viewChunk(player, original.call(player));
    }

    @WrapOperation(method = "updateChunkTracking", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;getPlayerViewDistance(Lnet/minecraft/server/level/ServerPlayer;)I"))
    private int seekerdrones$viewDistance(ChunkMap chunkMap, ServerPlayer player, Operation<Integer> original) {
        return FpvFeeds.viewDistance(player, original.call(chunkMap, player));
    }
}
