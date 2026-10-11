package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Particles sent by distance reach an FPV viewer by the drone's position (DESIGN.md section 2.12). */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @WrapOperation(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;ZDDDLnet/minecraft/network/protocol/Packet;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;blockPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos seekerdrones$viewBlock(ServerPlayer player, Operation<BlockPos> original) {
        return FpvFeeds.isViewing(player) ? BlockPos.containing(FpvFeeds.viewPosition(player, player.position())) : original.call(player);
    }
}
