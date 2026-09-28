package com.elpinho.seekerdrones.network;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.operator.OperatorGroup;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.mojang.authlib.GameProfile;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server to client: the Operator list of the Factory whose menu the player has open (DESIGN.md sections 6.1 and 7.1).
 * Only the group owner gets the list. Everyone else gets {@code owner = false} and an empty list.
 *
 * @param operators the operators other than the owner, sorted by name
 * @param message   feedback on the owner's last edit, if any
 */
public record FactoryOperatorsPayload(int containerId, boolean owner, List<Operator> operators, Optional<Component> message)
        implements CustomPacketPayload {
    public static final Type<FactoryOperatorsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "factory_operators"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryOperatorsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FactoryOperatorsPayload::containerId,
            ByteBufCodecs.BOOL, FactoryOperatorsPayload::owner,
            Operator.STREAM_CODEC.apply(ByteBufCodecs.list()), FactoryOperatorsPayload::operators,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, FactoryOperatorsPayload::message,
            FactoryOperatorsPayload::new);

    /** Sends the current list of the menu's Factory to the player, with optional feedback. */
    public static void send(ServerPlayer player, DroneFactoryMenu menu, @Nullable Component message) {
        DroneFactoryBlockEntity factory = menu.getFactory();
        Optional<OperatorGroup> group = factory == null ? Optional.empty()
                : factory.getGroupId().flatMap(id -> OperatorGroups.get(player.server).getGroup(id));
        boolean owner = group.isPresent() && group.get().owner().equals(player.getUUID());
        List<Operator> operators = owner
                ? group.get().operators().stream()
                        .map(uuid -> new Operator(uuid, playerName(player.server, uuid)))
                        .sorted(Comparator.comparing(Operator::name, String.CASE_INSENSITIVE_ORDER))
                        .toList()
                : List.of();
        PacketDistributor.sendToPlayer(player, new FactoryOperatorsPayload(menu.containerId, owner, operators, Optional.ofNullable(message)));
    }

    /** The player's name from the profile cache, or their UUID if it can't be resolved. */
    static String playerName(MinecraftServer server, UUID uuid) {
        return Optional.ofNullable(server.getProfileCache())
                .flatMap(cache -> cache.get(uuid))
                .map(GameProfile::getName)
                .orElse(uuid.toString());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Operator(UUID id, String name) {
        public static final StreamCodec<ByteBuf, Operator> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Operator::id,
                ByteBufCodecs.STRING_UTF8, Operator::name,
                Operator::new);
    }
}
