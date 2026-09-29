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
 * The server's target blacklist (DESIGN.md section 3.3): entity types that drones never target, from
 * {@code drone.targetBlacklist}. The config's type IDs and tags are resolved into one set of types the first time it's
 * needed, and again after the config or the tags change, so the scan's check is a single set lookup.
 */
public final class TargetBlacklist {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static volatile Resolved resolved;

    private record Resolved(Set<EntityType<?>> types, Set<TagKey<EntityType<?>>> tags) {}

    /** Whether drones may never target entities of this type. */
    public static boolean isBlacklisted(EntityType<?> type) {
        return resolve().types().contains(type);
    }

    /**
     * Whether a new target entry is refused because of the blacklist: a blacklisted entity type, a blacklisted tag, or
     * a tag whose entity types are all blacklisted. Player names are never refused (section 2.6).
     */
    public static boolean blocks(TargetEntry entry) {
        Resolved blacklist = resolve();
        if (entry.kind() == TargetEntry.Kind.PLAYER_NAME) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.value());
        if (id == null) {
            return false;
        }
        if (entry.kind() == TargetEntry.Kind.ENTITY_TYPE) {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(blacklist.types()::contains).orElse(false);
        }
        TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
        if (blacklist.tags().contains(tag)) {
            return true;
        }
        HolderSet.Named<EntityType<?>> members = BuiltInRegistries.ENTITY_TYPE.getTag(tag).orElse(null);
        if (members == null || members.size() == 0) {
            return false;
        }
        for (Holder<EntityType<?>> member : members) {
            if (!blacklist.types().contains(member.value())) {
                return false;
            }
        }
        return true;
    }

    /** Drops the resolved set, after the config or the entity tags change. */
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
            current = build(ServerConfig.get(ServerConfig.DRONE_TARGET_BLACKLIST));
            resolved = current;
        }
        return current;
    }

    private static Resolved build(List<? extends String> entries) {
        Set<EntityType<?>> types = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();
        for (String entry : entries) {
            boolean isTag = entry.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(isTag ? entry.substring(1) : entry);
            if (id == null) {
                LOGGER.warn("Ignoring invalid drone target blacklist entry {}", entry);
                continue;
            }
            if (isTag) {
                TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
                tags.add(tag);
                BuiltInRegistries.ENTITY_TYPE.getTag(tag).ifPresent(members -> members.forEach(member -> types.add(member.value())));
            } else {
                BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse(types::add,
                        () -> LOGGER.warn("Ignoring unknown entity type {} in the drone target blacklist", entry));
            }
        }
        return new Resolved(Set.copyOf(types), Set.copyOf(tags));
    }

    private TargetBlacklist() {}
}
