package com.elpinho.seekerdrones.drone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/**
 * A drone's target entries resolved for fast matching (DESIGN.md sections 2.6, 2.7 and 3.3). Built once from the
 * drone's data and cached on the entity; rebuild it whenever the data changes or {@link #isStale} says the server
 * config changed since it was built.
 */
public final class TargetMatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Entries already warned about, so a bad entry shared by many drones is only logged once. */
    private static final Set<String> LOGGED_INVALID = ConcurrentHashMap.newKeySet();

    /** Bumped when the server config changes, so cached matchers pick up a new {@code drone.targetSlots}. */
    private static volatile int configGeneration;

    public static final TargetMatcher EMPTY = new TargetMatcher(null, false, Set.of(), Set.of(), Set.of());

    /** The data the matcher was built from, for the group operator exemption. Null for {@link #EMPTY}. */
    @Nullable
    private final DroneData data;
    private final boolean playerSeek;
    private final Set<EntityType<?>> types;
    private final Set<TagKey<EntityType<?>>> tags;
    /** Lowercased player names. */
    private final Set<String> playerNames;
    private final int generation = configGeneration;

    private TargetMatcher(@Nullable DroneData data, boolean playerSeek, Set<EntityType<?>> types, Set<TagKey<EntityType<?>>> tags, Set<String> playerNames) {
        this.data = data;
        this.playerSeek = playerSeek;
        this.types = types;
        this.tags = tags;
        this.playerNames = playerNames;
    }

    /** Marks every cached matcher stale. Called when the server config changes. */
    public static void invalidateAll() {
        configGeneration++;
    }

    /** Whether the server config changed since this matcher was built. */
    public boolean isStale() {
        return generation != configGeneration;
    }

    public static TargetMatcher of(DroneData data) {
        Set<EntityType<?>> types = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();
        Set<String> playerNames = new HashSet<>();
        for (TargetEntry entry : activeEntries(data)) {
            switch (entry.kind()) {
                case ENTITY_TYPE -> {
                    ResourceLocation id = ResourceLocation.tryParse(entry.value());
                    Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
                    type.ifPresentOrElse(types::add, () -> logInvalid(entry));
                }
                case TAG -> {
                    ResourceLocation id = ResourceLocation.tryParse(entry.value());
                    if (id != null) {
                        tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
                    } else {
                        logInvalid(entry);
                    }
                }
                case PLAYER_NAME -> playerNames.add(entry.value().toLowerCase(Locale.ROOT));
            }
        }
        if (types.isEmpty() && tags.isEmpty() && playerNames.isEmpty()) {
            // A fresh instance rather than EMPTY, so it carries the current config generation.
            return new TargetMatcher(null, false, Set.of(), Set.of(), Set.of());
        }
        return new TargetMatcher(data, DroneStats.hasPlayerSeek(data), Set.copyOf(types), Set.copyOf(tags), Set.copyOf(playerNames));
    }

    /**
     * The entries a flying drone actually uses (section 2.7 fail-safe): the first {@code targetSlots} entries,
     * minus player names without Player Seek.
     */
    public static List<TargetEntry> activeEntries(DroneData data) {
        List<TargetEntry> targets = data.config().targets();
        List<TargetEntry> active = new ArrayList<>(targets.subList(0, Math.min(targets.size(), DroneStats.targetSlots())));
        if (!DroneStats.hasPlayerSeek(data)) {
            active.removeIf(entry -> entry.kind() == TargetEntry.Kind.PLAYER_NAME);
        }
        return active;
    }

    /**
     * The entries the drone can actually target, for display: {@link #activeEntries} minus entries the target
     * blacklist blocks (section 3.3).
     */
    public static List<TargetEntry> targetableEntries(DroneData data) {
        List<TargetEntry> entries = activeEntries(data);
        entries.removeIf(TargetBlacklist::blocks);
        return entries;
    }

    private static void logInvalid(TargetEntry entry) {
        if (LOGGED_INVALID.add(entry.kind().getSerializedName() + ":" + entry.value())) {
            LOGGER.warn("Ignoring invalid drone target entry {} ({})", entry.displayString(), entry.kind().getSerializedName());
        }
    }

    /** True if there is nothing to match, so scans can be skipped entirely. */
    public boolean isEmpty() {
        return types.isEmpty() && tags.isEmpty() && playerNames.isEmpty();
    }

    /** Whether {@code entity} is a valid target for {@code drone} (section 3.3). Server side only. */
    public boolean matches(DroneEntity drone, Entity entity) {
        if (isEmpty() || entity == drone || !entity.isAlive()) {
            return false;
        }
        EntityType<?> type = entity.getType();
        // Blacklisted types are never targets, whatever the entries say (section 3.3).
        if (TargetBlacklist.isBlacklisted(type)) {
            return false;
        }
        if (entity instanceof DroneEntity other) {
            // Only enemy drones: a drone's allies (same group, or same owner if it has no group) are exempt, like
            // operators. Unowned drones are enemies to everyone (section 3.3).
            TargetClaims.Team team = drone.getClaimTeam();
            return !(team.id() != null && team.equals(other.getClaimTeam())) && matchesType(type);
        }
        if (entity instanceof Player player) {
            if (!playerSeek || player.isSpectator() || player.isCreative()) {
                return false;
            }
            boolean listed = playerNames.contains(player.getGameProfile().getName().toLowerCase(Locale.ROOT)) || matchesType(type);
            if (!listed) {
                return false;
            }
            MinecraftServer server = drone.getServer();
            return server == null || !DronePermissions.isOperator(server, data, player.getUUID());
        }
        return matchesType(type);
    }

    private boolean matchesType(EntityType<?> type) {
        if (types.contains(type)) {
            return true;
        }
        for (TagKey<EntityType<?>> tag : tags) {
            if (type.is(tag)) {
                return true;
            }
        }
        return false;
    }
}
