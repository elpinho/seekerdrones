package com.elpinho.seekerdrones.commandcenter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DroneOrder;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Contact;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Details;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Kind;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.OwnDetails;
import com.elpinho.seekerdrones.network.CommandCenterTerrainPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.RemoteStationBlockEntity;
import com.elpinho.seekerdrones.radar.TerrainBuilder;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.remote.RemoteReach;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Drone Command Center (DESIGN.md section 7.10): the shared remote station with the widest map, sent in row slices.
 * Each viewer gets every drone in the area (their own, other players' and unowned ones) every scan interval, with
 * details every detail interval, plus the Charging Stations their drones could use. Orders given from its screen are
 * handled by {@link CommandCenterOrders}.
 */
public class CommandCenterBlockEntity extends RemoteStationBlockEntity<ColorMap> {
    /** Viewers who haven't had a detail snapshot since they opened the screen. */
    private final Set<UUID> needDetails = new HashSet<>();

    public CommandCenterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMMAND_CENTER.get(), pos, state);
    }

    // --- Config ---

    @Override
    protected int energyCapacity() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_ENERGY_CAPACITY);
    }

    @Override
    protected int energyPerTick() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_ENERGY_PER_TICK);
    }

    @Override
    public int radius() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_RADIUS);
    }

    @Override
    protected int scanInterval() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_SCAN_INTERVAL);
    }

    @Override
    protected int terrainRefreshInterval() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_TERRAIN_REFRESH_INTERVAL);
    }

    @Override
    protected int terrainChunksPerTick() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_TERRAIN_CHUNKS_PER_TICK);
    }

    @Override
    protected TerrainBuilder<ColorMap> newTerrainBuilder(ServerLevel level, int radius) {
        return TerrainBuilder.colors(level, worldPosition, radius);
    }

    @Override
    public RemoteReach noPowerReach() {
        return RemoteReach.COMMAND_CENTER_NO_POWER;
    }

    @Override
    public RemoteReach outOfAreaReach() {
        return RemoteReach.COMMAND_CENTER_OUT_OF_AREA;
    }

    // --- Orders (section 7.10) ---

    /**
     * The top of the top block at a spot, for Move and Strike, by the map's surface rule (section 7.8): in dimensions
     * with a ceiling, the highest non-air block at or below this station's Y, elsewhere the world surface. Empty if
     * the chunk isn't loaded or the column is empty.
     */
    public Optional<Vec3> orderTop(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, worldPosition.getY(), z);
        if (!level.hasChunkAt(column)) {
            return Optional.empty();
        }
        int top;
        if (level.dimensionType().hasCeiling()) {
            top = level.getMinBuildHeight() - 1;
            BlockPos.MutableBlockPos pos = column.mutable();
            for (int y = Math.min(worldPosition.getY(), level.getMaxBuildHeight() - 1); y >= level.getMinBuildHeight(); y--) {
                if (!level.getBlockState(pos.setY(y)).isAir()) {
                    top = y;
                    break;
                }
            }
        } else {
            top = level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()) - 1;
        }
        if (top < level.getMinBuildHeight()) {
            return Optional.empty();
        }
        return Optional.of(new Vec3(column.getX() + 0.5, top + 1, column.getZ() + 0.5));
    }

    // --- Contacts (section 7.10) ---

    @Override
    protected void addViewer(ServerPlayer player) {
        super.addViewer(player);
        needDetails.add(player.getUUID());
    }

    /** The detail ticks, staggered like the scan ticks. */
    private boolean isDetailTick(ServerLevel level) {
        return Math.floorMod(level.getGameTime() + worldPosition.hashCode(), ServerConfig.get(ServerConfig.COMMAND_CENTER_DETAIL_INTERVAL))
                < scanInterval();
    }

    @Override
    protected List<CustomPacketPayload> terrainPayloads(ColorMap terrain) {
        return new ArrayList<>(CommandCenterTerrainPayload.slices(worldPosition, terrain));
    }

    @Override
    protected CustomPacketPayload contactsPayload(ServerLevel level, ServerPlayer viewer) {
        boolean details = needDetails.remove(viewer.getUUID()) | isDetailTick(level);
        MinecraftServer server = level.getServer();
        OperatorGroups groups = OperatorGroups.get(server);
        Vec3 center = center();
        List<Contact> contacts = new ArrayList<>();
        for (DroneEntity drone : DroneIndex.drones(level)) {
            if (drone.isRemoved() || !inArea(drone.getX(), drone.getZ())) {
                continue;
            }
            float dx = (float) (drone.getX() - center.x);
            float dy = (float) (drone.getY() - worldPosition.getY());
            float dz = (float) (drone.getZ() - center.z);
            DroneData data = drone.snapshotData();
            // No server-operator bypass: the map is gameplay, not moderation.
            Kind kind = DronePermissions.isOperator(server, data, viewer.getUUID()) ? Kind.OWN
                    : isOwned(groups, data) ? Kind.OTHER : Kind.UNOWNED;
            float health = drone.getHealth();
            float maxHealth = drone.getMaxHealth();
            Optional<Details> detail = switch (kind) {
                case OWN -> details ? Optional.of(new Details(health, maxHealth, patrol(drone, data, center), Optional.of(own(drone, data))))
                        : Optional.empty();
                case OTHER -> details ? Optional.of(new Details(health, maxHealth, patrol(drone, data, center), Optional.empty())) : Optional.empty();
                case UNOWNED -> Optional.of(new Details(health, maxHealth, Optional.empty(), Optional.empty()));
            };
            boolean named = kind != Kind.UNOWNED;
            contacts.add(new Contact(kind, named ? drone.getDroneId() : "", dx, dy, dz, drone.getYRot(), named ? drone.getLabel() : "",
                    named ? drone.getColor() : DyeColor.GRAY, target(drone, center), detail));
        }
        return new CommandCenterContactsPayload(worldPosition, contacts, stations(level, viewer));
    }

    /** Whether the drone belongs to someone: an owner, or an owner group that exists (section 6.3). */
    private static boolean isOwned(OperatorGroups groups, DroneData data) {
        if (data.groupId().isPresent()) {
            return groups.getGroup(data.groupId().get()).isPresent();
        }
        return data.ownerId().isPresent();
    }

    private static OwnDetails own(DroneEntity drone, DroneData data) {
        DroneOrder.Info order = drone.getOrder();
        return new OwnDetails(data.energy(), DroneStats.maxEnergy(data), data.upgrades(), drone.getState(),
                Optional.ofNullable(order).map(info -> new CommandCenterContactsPayload.Order(info.order(), info.ticksLeft())));
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.command_center");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            addViewer(serverPlayer);
        }
        return new CommandCenterMenu(containerId, this);
    }
}
