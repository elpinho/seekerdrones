package com.elpinho.seekerdrones.client.sound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.registry.ModSounds;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The Explosive approach beep (DESIGN.md section 2.9), like a car parking sensor: an Explosive drone chasing its
 * target beeps slowly at the edge of {@code sounds.explosiveApproach.range} and faster as it gets closer to the
 * listener. Each beep is a short one-shot whose volume fades out linearly over the range (vanilla would fade a sound
 * this quiet out over 16 blocks, which a diving drone crosses in under a second).
 * <p>
 * {@link DroneSounds} calls {@link #pick} when it re-picks its loops, and {@link #tick} every tick to time the beeps.
 */
final class ApproachBeeps {
    /** Ticks until each beeping drone's next beep, by entity id. */
    private static final Int2IntMap NEXT_BEEP = new Int2IntOpenHashMap();

    static void clear() {
        NEXT_BEEP.clear();
    }

    /**
     * Picks the nearest approaching Explosive drones, also those still out of range, so a drone that locks on from far
     * away beeps the moment it comes into range instead of at the next re-pick. A newly picked drone beeps at once.
     */
    static void pick(List<DroneEntity> drones, Vec3 listener) {
        List<DroneEntity> candidates = new ArrayList<>();
        for (DroneEntity drone : drones) {
            if (drone.isApproachingExplosive()) {
                candidates.add(drone);
            }
        }
        candidates.sort(Comparator.comparingDouble(drone -> drone.distanceToSqr(listener)));
        Int2IntMap kept = new Int2IntOpenHashMap();
        int max = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_MAX_PLAYING);
        for (int i = 0; i < Math.min(max, candidates.size()); i++) {
            int id = candidates.get(i).getId();
            kept.put(id, NEXT_BEEP.getOrDefault(id, 0));
        }
        NEXT_BEEP.clear();
        NEXT_BEEP.putAll(kept);
    }

    /** Counts down each picked drone's timer and plays a beep when it runs out. */
    static void tick(ClientLevel level, SoundManager sounds, Vec3 listener) {
        if (NEXT_BEEP.isEmpty()) {
            return;
        }
        ObjectIterator<Int2IntMap.Entry> iterator = NEXT_BEEP.int2IntEntrySet().iterator();
        while (iterator.hasNext()) {
            Int2IntMap.Entry entry = iterator.next();
            Entity entity = level.getEntity(entry.getIntKey());
            if (!(entity instanceof DroneEntity drone) || drone.isRemoved() || !drone.isApproachingExplosive()) {
                iterator.remove();
                continue;
            }
            if (entry.getIntValue() > 0) {
                entry.setValue(entry.getIntValue() - 1);
                continue;
            }
            double distance = Math.sqrt(drone.distanceToSqr(listener));
            if (!DroneStats.inApproachBeepRange(distance)) {
                // Out of range: wait, and beep as soon as it comes in.
                continue;
            }
            entry.setValue(DroneStats.approachBeepInterval(distance) - 1);
            if (!drone.isSilent()) {
                float volume = DroneStats.approachBeepVolume(distance);
                float pitch = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_PITCH).floatValue();
                sounds.play(new SimpleSoundInstance(ModSounds.DRONE_EXPLOSIVE_APPROACH.get().getLocation(), SoundSource.NEUTRAL, volume, pitch,
                        SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, drone.getX(), drone.getY(), drone.getZ(),
                        false));
            }
        }
    }

    private ApproachBeeps() {}
}
