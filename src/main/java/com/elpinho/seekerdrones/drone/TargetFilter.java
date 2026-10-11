package com.elpinho.seekerdrones.drone;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * The server's target filter (DESIGN.md section 3.3): entity types that drones never target, from
 * {@code drone.targetDenylist}, and, while {@code drone.targetAllowlist} isn't empty, every type it doesn't list. The
 * config's type IDs and tags are resolved into sets of types the first time they're needed, and again after the config
 * or the tags change, so the scan's check is a set lookup or two.
 */
public final class TargetFilter {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static volatile Resolved resolved;

    private record Lists(Set<EntityType<?>> types, Set<TagKey<EntityType<?>>> tags) {}

    /** The allowlist is null while it's empty, so everything not denylisted is allowed. */
    private record Resolved(Lists denylist, @Nullable Lists allowlist) {}

    /** Whether drones may never target entities of this type. */
    public static boolean isBlocked(EntityType<?> type) {
        Resolved filter = resolve();
        return filter.denylist().types().contains(type) || filter.allowlist() != null && !filter.allowlist().types().contains(type);
    }

    /**
     * Whether a new target entry is refused because of the filter: a blocked entity type, a denylisted tag, a tag the
     * allowlist doesn't list (while it's active) or a tag whose entity types are all blocked. Player names are refused
     * while players are blocked.
     */
    public static boolean blocks(TargetEntry entry) {
        Resolved filter = resolve();
        if (entry.kind() == TargetEntry.Kind.PLAYER_NAME) {
            return isBlocked(EntityType.PLAYER);
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.value());
        if (id == null) {
            return false;
        }
        if (entry.kind() == TargetEntry.Kind.ENTITY_TYPE) {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(TargetFilter::isBlocked).orElse(false);
        }
        TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
        if (filter.denylist().tags().contains(tag)) {
            return true;
        }
        HolderSet.Named<EntityType<?>> members = BuiltInRegistries.ENTITY_TYPE.getTag(tag).orElse(null);
        if (members == null || members.size() == 0) {
            // An empty tag matches nothing anyway; only an active allowlist that doesn't list it refuses it.
            return filter.allowlist() != null && !filter.allowlist().tags().contains(tag);
        }
        for (Holder<EntityType<?>> member : members) {
            if (!isBlocked(member.value())) {
                return false;
            }
        }
        return true;
    }

    /** Drops the resolved sets, after the config or the entity tags change. */
    public static void invalidate() {
        resolved = null;
    }

    /** Whether a config list element is a well-formed entity type ID or {@code #tag} (it may still name nothing). */
    public static boolean isWellFormed(Object element) {
        if (!(element instanceof String text)) {
            return false;
        }
        String id = text.startsWith("#") ? text.substring(1) : text;
        return ResourceLocation.tryParse(id) != null;
    }

    private static Resolved resolve() {
        Resolved current = resolved;
        if (current == null) {
            List<? extends String> allowlist = ServerConfig.get(ServerConfig.DRONE_TARGET_ALLOWLIST);
            current = new Resolved(build(ServerConfig.get(ServerConfig.DRONE_TARGET_DENYLIST), "denylist"),
                    allowlist.isEmpty() ? null : build(allowlist, "allowlist"));
            resolved = current;
        }
        return current;
    }

    private static Lists build(List<? extends String> entries, String listName) {
        Set<EntityType<?>> types = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();
        for (String entry : entries) {
            boolean isTag = entry.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(isTag ? entry.substring(1) : entry);
            if (id == null) {
                LOGGER.warn("Ignoring invalid drone target {} entry {}", listName, entry);
                continue;
            }
            if (isTag) {
                TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
                tags.add(tag);
                BuiltInRegistries.ENTITY_TYPE.getTag(tag).ifPresent(members -> members.forEach(member -> types.add(member.value())));
            } else {
                BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse(types::add,
                        () -> LOGGER.warn("Ignoring unknown entity type {} in the drone target {}", entry, listName));
            }
        }
        return new Lists(Set.copyOf(types), Set.copyOf(tags));
    }

    private TargetFilter() {}
}
