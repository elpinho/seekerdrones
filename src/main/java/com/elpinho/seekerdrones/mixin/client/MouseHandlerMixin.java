package com.elpinho.seekerdrones.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.client.feed.FpvFeedClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

/** While watching an FPV feed, the mouse turns the drone's camera gimbal instead of the player (DESIGN.md section 2.12). */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void seekerdrones$turnGimbal(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        if (!FpvFeedClient.turn(yaw, pitch)) {
            original.call(player, yaw, pitch);
        }
    }
}
