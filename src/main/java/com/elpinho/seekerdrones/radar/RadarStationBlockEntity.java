package com.elpinho.seekerdrones.radar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.machine.IntMenuData;
import com.elpinho.seekerdrones.machine.MachineEnergy;
import com.elpinho.seekerdrones.network.RadarContactsPayload;
import com.elpinho.seekerdrones.network.RadarTerrainPayload;
import com.elpinho.seekerdrones.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone Radar Station (DESIGN.md section 7.8). It uses FE every tick while it has enough. While powered and while
 * someone has its screen open, it builds the terrain map (cached here, not saved) and sends each viewer their own
 * drones every {@code scanInterval} ticks. With nobody watching it does no work besides using FE.
 */
public class RadarStationBlockEntity extends BlockEntity implements MenuProvider {
    private static final String TAG_ENERGY = "Energy";

    private final MachineEnergy energy = new MachineEnergy(() -> ServerConfig.get(ServerConfig.RADAR_STATION_ENERGY_CAPACITY), this::setChanged);
    private final ContainerData data = IntMenuData.server(RadarStationMenu.DATA_VALUES, index -> switch (index) {
        case RadarStationMenu.DATA_ENERGY -> energy.getEnergyStored();
        case RadarStationMenu.DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
        case RadarStationMenu.DATA_ENERGY_PER_TICK -> energyPerTick();
        case RadarStationMenu.DATA_POWERED -> this.powered ? 1 : 0;
        default -> 0;
    });

    /** Players with the screen open. True for those who still need the current map, e.g. just after opening. */
    private final Map<ServerPlayer, Boolean> viewers = new LinkedHashMap<>();
    private boolean powered;
    @Nullable
    private TerrainMap terrain;
    private long terrainBuiltAt;
    @Nullable
    private TerrainBuilder builder;
    /** Client only: where the radar bar points, in degrees, so it stays put while unpowered. Not saved. */
    public float barAngle;

    public RadarStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADAR_STATION.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public boolean isPowered() {
        return powered;
    }

    // --- Viewers ---

    /** Called when the player opens the screen. Data starts on the next tick, after the screen has opened on their side. */
    void addViewer(ServerPlayer player) {
        viewers.put(player, true);
    }

    void removeViewer(ServerPlayer player) {
        viewers.remove(player);
    }

    // --- Processing ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, RadarStationBlockEntity station) {
        station.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        setPowered(level, energy.consume(energyPerTick()));
        viewers.keySet().removeIf(player -> player.isRemoved()
                || !(player.containerMenu instanceof RadarStationMenu menu && menu.getPos().equals(worldPosition)));
        if (!powered || viewers.isEmpty()) {
            return;
        }
        updateTerrain(level);
        boolean scanTick = Math.floorMod(level.getGameTime() + worldPosition.hashCode(), scanInterval()) == 0;
        for (Map.Entry<ServerPlayer, Boolean> viewer : viewers.entrySet()) {
            boolean isNew = viewer.getValue();
            if (isNew && terrain != null) {
                PacketDistributor.sendToPlayer(viewer.getKey(), new RadarTerrainPayload(worldPosition, terrain));
                viewer.setValue(false);
            }
            if (scanTick || isNew) {
                PacketDistributor.sendToPlayer(viewer.getKey(), new RadarContactsPayload(worldPosition, contacts(level, viewer.getKey())));
            }
        }
    }

    /** Starts a rebuild when the map is missing or older than the refresh interval, and sends it to everyone once it's done. */
    private void updateTerrain(ServerLevel level) {
        int radius = ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS);
        boolean stale = terrain == null || terrain.radius() != radius
                || level.getGameTime() - terrainBuiltAt >= ServerConfig.get(ServerConfig.RADAR_STATION_TERRAIN_REFRESH_INTERVAL);
        if (builder == null && stale) {
            builder = new TerrainBuilder(level, worldPosition, radius);
        }
        if (builder == null) {
            return;
        }
        TerrainMap built = builder.tick(level, ServerConfig.get(ServerConfig.RADAR_STATION_TERRAIN_CHUNKS_PER_TICK));
        if (built != null) {
            builder = null;
            terrain = built;
            terrainBuiltAt = level.getGameTime();
            viewers.replaceAll((player, needsMap) -> true);
        }
    }

    /** The viewer's own drones inside the circle (section 6.3, no server-operator bypass). */
    private List<RadarContactsPayload.Contact> contacts(ServerLevel level, ServerPlayer viewer) {
        int radius = ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS);
        Vec3 center = Vec3.atCenterOf(worldPosition);
        List<RadarContactsPayload.Contact> contacts = new ArrayList<>();
        for (DroneEntity drone : DroneIndex.drones(level)) {
            double dx = drone.getX() - center.x;
            double dz = drone.getZ() - center.z;
            if (drone.isRemoved() || dx * dx + dz * dz > (double) radius * radius) {
                continue;
            }
            DroneData droneData = drone.snapshotData();
            if (DronePermissions.isOperator(level.getServer(), droneData, viewer.getUUID())) {
                contacts.add(new RadarContactsPayload.Contact(drone.getDroneId(), (float) dx, (float) dz, drone.getLabel(), drone.getColor()));
            }
        }
        return contacts;
    }

    /** Only writes the block state when it changes (section 7.6). */
    private void setPowered(Level level, boolean powered) {
        this.powered = powered;
        BlockState state = getBlockState();
        if (state.getValue(RadarStationBlock.POWERED) != powered) {
            level.setBlock(worldPosition, state.setValue(RadarStationBlock.POWERED, powered), Block.UPDATE_CLIENTS);
        }
    }

    private static int energyPerTick() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_ENERGY_PER_TICK);
    }

    private static int scanInterval() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_SCAN_INTERVAL);
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.radar_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            addViewer(serverPlayer);
        }
        return new RadarStationMenu(containerId, this, data);
    }

    // --- Saving ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energy.getEnergyStored());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.load(tag.getInt(TAG_ENERGY));
    }
}
