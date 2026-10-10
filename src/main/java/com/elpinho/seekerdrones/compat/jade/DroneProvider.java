package com.elpinho.seekerdrones.compat.jade;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;

/**
 * A drone's ID for everyone, plus its state, owner, targets and current target for its operators (the same players
 * who may open its status screen, DESIGN.md section 2.4). The name and health are Jade's own lines.
 */
public enum DroneProvider implements IEntityComponentProvider, StreamServerDataProvider<EntityAccessor, DroneProvider.Data> {
    INSTANCE;

    private static final ResourceLocation UID = SeekerDronesJadePlugin.id("drone");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public Data streamData(EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof DroneEntity drone)) {
            return null;
        }
        DroneData data = drone.snapshotData();
        Optional<Details> details = Optional.empty();
        if (DronePermissions.canInteract(accessor.getPlayer(), data)) {
            Entity target = drone.getSeekTarget();
            details = Optional.of(new Details(ownerName(accessor.getLevel().getServer(), data), TargetMatcher.targetableEntries(data),
                    Optional.ofNullable(target).map(Entity::getName)));
        }
        return new Data(data.droneId(), details);
    }

    /** The group owner's name for a drone in an Operator Group, else its owner's. Empty for unowned drones. */
    private static String ownerName(MinecraftServer server, DroneData data) {
        if (server != null && data.groupId().isPresent()) {
            return OperatorGroups.get(server).getGroup(data.groupId().get())
                    .map(group -> StationStatusPayload.playerName(server, group.owner()))
                    .orElse("");
        }
        return data.ownerName();
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Data> streamCodec() {
        return Data.STREAM_CODEC;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        Optional<Data> decoded = decodeFromData(accessor);
        if (decoded.isEmpty() || !(accessor.getEntity() instanceof DroneEntity drone)) {
            return;
        }
        Data data = decoded.get();
        tooltip.add(Component.translatable("jade.seekerdrones.drone.id", data.droneId()));
        data.details().ifPresent(details -> {
            // The state is synced to the client already, so it isn't sent again.
            DroneState state = drone.getState();
            Component stateName = Component.translatable(state.getTranslationKey());
            tooltip.add(details.target().isPresent() && (state == DroneState.CHASING || state == DroneState.FOLLOWING)
                    ? Component.translatable("jade.seekerdrones.drone.state_target", stateName, details.target().get())
                    : Component.translatable("jade.seekerdrones.drone.state", stateName));
            if (!details.owner().isEmpty()) {
                tooltip.add(Component.translatable("jade.seekerdrones.drone.owner", details.owner()));
            }
            tooltip.add(details.targets().isEmpty()
                    ? Component.translatable("jade.seekerdrones.drone.targets.none")
                    : Component.translatable("jade.seekerdrones.drone.targets", targetList(details.targets())));
        });
    }

    /** The target entries by name: entity types by their display name, tags with a {@code #}, player names as typed. */
    private static Component targetList(List<TargetEntry> targets) {
        MutableComponent list = Component.empty();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                list.append(", ");
            }
            TargetEntry entry = targets.get(i);
            list.append(entry.kind() == TargetEntry.Kind.ENTITY_TYPE
                    ? Optional.ofNullable(ResourceLocation.tryParse(entry.value()))
                            .flatMap(BuiltInRegistries.ENTITY_TYPE::getOptional)
                            .map(type -> (Component) type.getDescription())
                            .orElse(Component.literal(entry.value()))
                    : Component.literal(entry.displayString()));
        }
        return list;
    }

    /**
     * @param details the operator-only lines, empty for other players
     */
    public record Data(String droneId, Optional<Details> details) {
        static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::droneId,
                ByteBufCodecs.optional(Details.STREAM_CODEC), Data::details,
                Data::new);
    }

    /**
     * @param owner  the owner's name, empty for an unowned drone
     * @param target the name of the entity being chased or followed
     */
    public record Details(String owner, List<TargetEntry> targets, Optional<Component> target) {
        static final StreamCodec<RegistryFriendlyByteBuf, Details> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Details::owner,
                TargetEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), Details::targets,
                ByteBufCodecs.optional(ComponentSerialization.STREAM_CODEC), Details::target,
                Details::new);
    }
}
