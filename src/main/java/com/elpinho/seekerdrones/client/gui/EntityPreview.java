package com.elpinho.seekerdrones.client.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.elpinho.seekerdrones.drone.TargetEntry;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Client-side previews of target entries (DESIGN.md section 7.7). An entity type shows that entity
 * turning slowly. A tag cycles through its members. A player name shows a player with their tab-list skin (or the
 * default skin for their name). Entities are created with {@link EntityType#create} on first use and cached for as
 * long as this object lives, which is while a screen is open. They are never added to the level. If an entity can't
 * be created or throws while rendering, its spawn egg is drawn instead.
 */
public class EntityPreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Each member of a tag is shown this long. */
    private static final long CYCLE_MILLIS = 1800;
    /** Turning speed, in degrees per second. */
    private static final float TURN_SPEED = 36;
    private static final float PITCH = 12;
    /** Entities fill this much of their box, leaving room around them. */
    private static final float SIZE = 0.75F;

    private final Map<EntityType<?>, Entity> entities = new HashMap<>();
    private final Map<String, Entity> players = new HashMap<>();
    private final Set<Object> failed = new HashSet<>();

    /** What an entry shows right now: an entity type, or a player name. */
    private record Shown(@Nullable EntityType<?> type, @Nullable String player) {}

    /**
     * Draws the entry's preview in the box, standing on its bottom edge.
     *
     * @param phase offsets the turn and the tag cycle, so several previews side by side don't move in step
     */
    public void render(GuiGraphics graphics, TargetEntry entry, int x, int y, int width, int height, int phase) {
        render(graphics, entry, x, y, width, height, phase, 1);
    }

    /** {@link #render} with the entity drawn at {@code size} times its usual fit in the box. */
    public void render(GuiGraphics graphics, TargetEntry entry, int x, int y, int width, int height, int phase, float size) {
        Shown shown = shown(entry, phase);
        if (shown == null) {
            graphics.renderItem(new ItemStack(Items.NAME_TAG), x + (width - 16) / 2, y + (height - 16) / 2);
            return;
        }
        Object key = shown.type() != null ? shown.type() : shown.player();
        Entity entity = failed.contains(key) ? null : entity(shown);
        if (entity == null) {
            failed.add(key);
            graphics.renderItem(fallbackIcon(shown), x + (width - 16) / 2, y + (height - 16) / 2);
            return;
        }
        float fitHeight = (height - 6) / Math.max(0.3F, entity.getBbHeight());
        float fitWidth = (width - 4) / Math.max(0.3F, entity.getBbWidth() * 1.3F);
        float scale = Math.min(fitHeight, fitWidth) * SIZE * size;
        float yaw = ((Util.getMillis() + phase * 500L) / 1000F * TURN_SPEED) % 360;
        graphics.enableScissor(x, y, x + width, y + height);
        try {
            EntityRendering.render(graphics, entity, x + width / 2F, y + height - 3, scale, yaw, PITCH, false);
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't render a target preview of {}, showing its spawn egg instead", key, e);
            failed.add(key);
        } finally {
            graphics.disableScissor();
        }
    }

    /** The name of what the entry shows right now: the entity type (for a tag, the current member) or the player. */
    public Component caption(TargetEntry entry, int phase) {
        Shown shown = shown(entry, phase);
        if (shown == null) {
            return Component.literal(entry.displayString());
        }
        return shown.type() != null ? shown.type().getDescription() : Component.literal(shown.player());
    }

    /** The members of a tag entry, empty for other kinds or unknown tags. */
    public static List<EntityType<?>> tagMembers(TargetEntry entry) {
        if (entry.kind() != TargetEntry.Kind.TAG) {
            return List.of();
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.value());
        if (id == null) {
            return List.of();
        }
        return BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id))
                .map(set -> set.stream().<EntityType<?>>map(Holder::value).toList())
                .orElse(List.of());
    }

    @Nullable
    private static Shown shown(TargetEntry entry, int phase) {
        return switch (entry.kind()) {
            case ENTITY_TYPE -> EntityType.byString(entry.value()).map(type -> new Shown(type, null)).orElse(null);
            case TAG -> {
                List<EntityType<?>> members = tagMembers(entry);
                if (members.isEmpty()) {
                    yield null;
                }
                int index = (int) ((Util.getMillis() + phase * 500L) / CYCLE_MILLIS % members.size());
                yield new Shown(members.get(index), null);
            }
            case PLAYER_NAME -> new Shown(null, entry.value());
        };
    }

    @Nullable
    private Entity entity(Shown shown) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        try {
            if (shown.type() != null) {
                return entities.computeIfAbsent(shown.type(), type -> prepared(type.create(level)));
            }
            return players.computeIfAbsent(shown.player(), name -> prepared(new RemotePlayer(level, profile(name))));
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't create a target preview of {}, showing its spawn egg instead", shown, e);
            return null;
        }
    }

    @Nullable
    private static Entity prepared(@Nullable Entity entity) {
        if (entity != null) {
            EntityRendering.preparePreview(entity);
        }
        return entity;
    }

    /**
     * The player's profile from the tab list, so the preview uses the skin the server sent. Players who aren't online
     * get an offline profile, which shows the default skin for their name.
     */
    private static GameProfile profile(String name) {
        var connection = Minecraft.getInstance().getConnection();
        return Optional.ofNullable(connection)
                .map(c -> c.getPlayerInfo(name))
                .map(PlayerInfo::getProfile)
                .orElseGet(() -> new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name));
    }

    /** Draws a player's face with their tab-list skin, or the default skin for their name. */
    public static void drawFace(GuiGraphics graphics, String name, int x, int y, int size) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection != null && !name.isEmpty() ? connection.getPlayerInfo(name) : null;
        PlayerFaceRenderer.draw(graphics, info != null ? info.getSkin() : DefaultPlayerSkin.get(UUIDUtil.createOfflinePlayerUUID(name)),
                x, y, size);
    }

    private static ItemStack fallbackIcon(Shown shown) {
        if (shown.type() == null) {
            return new ItemStack(Items.PLAYER_HEAD);
        }
        SpawnEggItem egg = SpawnEggItem.byId(shown.type());
        return new ItemStack(egg != null ? egg : Items.NAME_TAG);
    }
}
