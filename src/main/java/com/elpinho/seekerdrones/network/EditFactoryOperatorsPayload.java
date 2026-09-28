package com.elpinho.seekerdrones.network;

import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.operator.OperatorGroup;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.mojang.authlib.GameProfile;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the Factory owner adds an operator by name or removes one (DESIGN.md section 6.1). The server
 * checks that the sender has this Factory's menu open and owns its group.
 *
 * @param add    true to add {@code player} (a name), false to remove {@code player} (a UUID)
 */
public record EditFactoryOperatorsPayload(int containerId, boolean add, String player) implements CustomPacketPayload {
    public static final Type<EditFactoryOperatorsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "edit_factory_operators"));

    public static final StreamCodec<ByteBuf, EditFactoryOperatorsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EditFactoryOperatorsPayload::containerId,
            ByteBufCodecs.BOOL, EditFactoryOperatorsPayload::add,
            ByteBufCodecs.stringUtf8(64), EditFactoryOperatorsPayload::player,
            EditFactoryOperatorsPayload::new);

    private static final String KEY = "screen.seekerdrones.drone_factory.operators.";

    public static EditFactoryOperatorsPayload add(int containerId, String name) {
        return new EditFactoryOperatorsPayload(containerId, true, name);
    }

    public static EditFactoryOperatorsPayload remove(int containerId, UUID player) {
        return new EditFactoryOperatorsPayload(containerId, false, player.toString());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditFactoryOperatorsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof DroneFactoryMenu menu)
                || menu.containerId != payload.containerId() || !menu.stillValid(player)) {
            return;
        }
        DroneFactoryBlockEntity factory = menu.getFactory();
        if (factory == null || factory.getGroupId().isEmpty()) {
            return;
        }
        UUID groupId = factory.getGroupId().get();
        OperatorGroups groups = OperatorGroups.get(player.server);
        Optional<OperatorGroup> group = groups.getGroup(groupId);
        if (group.isEmpty() || !group.get().owner().equals(player.getUUID())) {
            return;
        }
        Component message = payload.add()
                ? add(player.server, groups, groupId, group.get(), payload.player().trim())
                : remove(player.server, groups, groupId, payload.player());
        FactoryOperatorsPayload.send(player, menu, message);
    }

    private static Component add(MinecraftServer server, OperatorGroups groups, UUID groupId, OperatorGroup group, String name) {
        Optional<GameProfile> profile = findProfile(server, name);
        if (profile.isEmpty()) {
            return Component.translatable(KEY + "unknown_player", name);
        }
        String resolvedName = profile.get().getName();
        if (group.isOperator(profile.get().getId())) {
            return Component.translatable(KEY + "already_operator", resolvedName);
        }
        groups.addOperator(groupId, profile.get().getId());
        return Component.translatable(KEY + "added", resolvedName);
    }

    private static Component remove(MinecraftServer server, OperatorGroups groups, UUID groupId, String player) {
        UUID id;
        try {
            id = UUID.fromString(player);
        } catch (IllegalArgumentException e) {
            return Component.empty();
        }
        String name = FactoryOperatorsPayload.playerName(server, id);
        return groups.removeOperator(groupId, id)
                ? Component.translatable(KEY + "removed", name)
                : Component.translatable(KEY + "not_operator", name);
    }

    /** Online players first, then anyone the server has seen before (its profile cache). */
    private static Optional<GameProfile> findProfile(MinecraftServer server, String name) {
        if (name.isEmpty()) {
            return Optional.empty();
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        return Optional.ofNullable(server.getProfileCache()).flatMap(cache -> cache.get(name));
    }
}
