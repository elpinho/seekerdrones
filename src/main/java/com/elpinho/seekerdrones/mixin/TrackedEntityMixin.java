package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** A player watching an FPV feed sees the entities around the drone, not around their body (DESIGN.md section 2.12). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 seekerdrones$viewPosition(ServerPlayer player, Operation<Vec3> original) {
        return FpvFeeds.viewPosition(player, original.call(player));
    }
}
