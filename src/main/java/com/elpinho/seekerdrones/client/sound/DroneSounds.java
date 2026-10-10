package com.elpinho.seekerdrones.client.sound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.LowPowerLevel;
import com.elpinho.seekerdrones.registry.ModSounds;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Picks which drones play the flying and low-power loops (DESIGN.md section 2.9). Sound channels are limited, so only
 * the nearest {@code sounds.<loop>.maxPlaying} drones in earshot play each loop, re-picked every
 * {@code sounds.resortInterval} ticks, and right away when a drone's low-power level or Explosive approach changes.
 * An Explosive drone chasing a target goes first for the flying loop, so a kamikaze is never silent in a swarm.
 */
public final class DroneSounds {
    private static final Int2ObjectMap<DroneFlyingSound> FLYING = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectMap<DroneLowPowerSound> LOW_POWER = new Int2ObjectOpenHashMap<>();

    private static boolean dirty;
    private static int untilResort;
    @Nullable
    private static ClientLevel lastLevel;

    /** Re-picks the playing drones on the next tick. */
    public static void markDirty() {
        dirty = true;
    }

    /** Forgets every loop, e.g. when leaving a world. The game stops the sounds themselves. */
    public static void clear() {
        FLYING.clear();
        LOW_POWER.clear();
        lastLevel = null;
        dirty = true;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) {
            // A new world or dimension: the game stopped all sounds.
            clear();
            lastLevel = level;
        }
        if (level == null || minecraft.isPaused()) {
            return;
        }
        SoundManager sounds = minecraft.getSoundManager();
        FLYING.values().removeIf(sound -> sound.isStopped() || !sounds.isActive(sound));
        LOW_POWER.values().removeIf(sound -> sound.isStopped() || !sounds.isActive(sound));
        if (!dirty && --untilResort > 0) {
            return;
        }
        dirty = false;
        untilResort = ServerConfig.get(ServerConfig.SOUNDS_RESORT_INTERVAL);

        Vec3 listener = minecraft.gameRenderer.getMainCamera().getPosition();
        List<DroneEntity> drones = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone && drone.isAlive()) {
                drones.add(drone);
            }
        }
        pick(sounds, drones, listener, FLYING, ServerConfig.get(ServerConfig.SOUNDS_FLYING_MAX_PLAYING), drone -> true,
                drone -> ModSounds.DRONE_FLY.get().getRange(DroneFlyingSound.maxVolume(drone)), true, DroneFlyingSound::new);
        pick(sounds, drones, listener, LOW_POWER, ServerConfig.get(ServerConfig.SOUNDS_LOW_POWER_MAX_PLAYING),
                drone -> drone.getLowPower() != LowPowerLevel.NONE,
                drone -> ModSounds.DRONE_LOW_POWER.get().getRange(DroneLowPowerSound.volume()), false,
                drone -> new DroneLowPowerSound(drone, drone.getLowPower()));
    }

    /**
     * Plays the loop for the nearest {@code max} drones that want it and are in earshot, and stops it for the rest.
     *
     * @param explosiveFirst whether approaching Explosive drones go before nearer ones
     */
    private static <S extends DroneLoopSound> void pick(SoundManager sounds, List<DroneEntity> drones, Vec3 listener, Int2ObjectMap<S> playing,
            int max, Predicate<DroneEntity> wants, ToDoubleFunction<DroneEntity> range, boolean explosiveFirst, Function<DroneEntity, S> create) {
        List<DroneEntity> candidates = new ArrayList<>();
        for (DroneEntity drone : drones) {
            double reach = range.applyAsDouble(drone);
            if (wants.test(drone) && drone.distanceToSqr(listener) <= reach * reach) {
                candidates.add(drone);
            }
        }
        Comparator<DroneEntity> order = Comparator.comparingDouble(drone -> drone.distanceToSqr(listener));
        if (explosiveFirst) {
            order = Comparator.<DroneEntity, Boolean>comparing(drone -> !drone.isApproachingExplosive()).thenComparing(order);
        }
        candidates.sort(order);
        IntSet chosen = new IntOpenHashSet();
        for (int i = 0; i < Math.min(max, candidates.size()); i++) {
            DroneEntity drone = candidates.get(i);
            chosen.add(drone.getId());
            S current = playing.get(drone.getId());
            if (current != null && current.isCurrent()) {
                continue;
            }
            if (current != null) {
                sounds.stop(current);
            }
            S sound = create.apply(drone);
            playing.put(drone.getId(), sound);
            sounds.play(sound);
        }
        playing.int2ObjectEntrySet().removeIf(entry -> {
            if (chosen.contains(entry.getIntKey())) {
                return false;
            }
            sounds.stop(entry.getValue());
            return true;
        });
    }

    private DroneSounds() {}
}
