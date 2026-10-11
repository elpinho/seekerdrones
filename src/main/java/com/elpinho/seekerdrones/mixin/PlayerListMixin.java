package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** Sounds and level events sent by distance reach an FPV viewer by the drone's position (DESIGN.md section 2.12). */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
    private double seekerdrones$viewX(ServerPlayer player, Operation<Double> original) {
        DroneEntity drone = FpvFeeds.viewedDrone(player);
        return drone != null ? drone.getX() : original.call(player);
    }

    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
    private double seekerdrones$viewY(ServerPlayer player, Operation<Double> original) {
        DroneEntity drone = FpvFeeds.viewedDrone(player);
        return drone != null ? drone.getY() : original.call(player);
    }

    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
    private double seekerdrones$viewZ(ServerPlayer player, Operation<Double> original) {
        DroneEntity drone = FpvFeeds.viewedDrone(player);
        return drone != null ? drone.getZ() : original.call(player);
    }
}
