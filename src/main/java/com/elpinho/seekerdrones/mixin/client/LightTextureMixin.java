package com.elpinho.seekerdrones.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.elpinho.seekerdrones.client.feed.FpvFeedClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

/** The FPV feed's night vision lights the world like the Night Vision effect (DESIGN.md section 2.12). */
@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @WrapOperation(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z", ordinal = 0))
    private boolean seekerdrones$feedNightVision(LocalPlayer player, Holder<MobEffect> effect, Operation<Boolean> original) {
        return FpvFeedClient.nightVision() || original.call(player, effect);
    }

    @WrapOperation(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float seekerdrones$feedNightVisionScale(LivingEntity entity, float partialTick, Operation<Float> original) {
        return FpvFeedClient.nightVision() ? 1.0F : original.call(entity, partialTick);
    }
}
