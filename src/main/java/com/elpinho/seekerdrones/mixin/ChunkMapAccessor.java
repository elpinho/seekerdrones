package com.elpinho.seekerdrones.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

/** Re-centers a player's chunk tracking right when an FPV feed starts or ends (DESIGN.md section 2.12). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("updateChunkTracking")
    void seekerdrones$updateChunkTracking(ServerPlayer player);
}
