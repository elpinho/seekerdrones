package com.elpinho.seekerdrones.radar;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.IntMenuData;
import com.elpinho.seekerdrones.machine.MachineEnergy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The radar machines' shared block entity (DESIGN.md sections 7.8 and 7.9). It uses FE every tick while it has
 * enough, and writes {@link TwoBlockMachineBlock#POWERED} only when that changes. While powered and while someone has
 * its screen open, it builds the terrain map (cached here, not saved), sends it to new viewers and after each rebuild,
 * and sends each viewer their contacts every scan interval. With nobody watching it does no work besides using FE and
 * {@link #tickPowered}.
 */
public abstract class RadarMachineBlockEntity<M extends RadarMap> extends BlockEntity implements MenuProvider {
    private static final String TAG_ENERGY = "Energy";

    protected final MachineEnergy energy = new MachineEnergy(this::energyCapacity, this::markEnergyChanged);
    protected final ContainerData data = IntMenuData.server(RadarMachineMenu.DATA_VALUES, index -> switch (index) {
        case RadarMachineMenu.DATA_ENERGY -> energy.getEnergyStored();
        case RadarMachineMenu.DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
        case RadarMachineMenu.DATA_ENERGY_PER_TICK -> energyPerTick();
        case RadarMachineMenu.DATA_POWERED -> this.powered ? 1 : 0;
        default -> 0;
    });

    /** Players with the screen open. True for those who still need the current map, e.g. just after opening. */
    private final Map<ServerPlayer, Boolean> viewers = new LinkedHashMap<>();
    private boolean powered;
    @Nullable
    private M terrain;
    private long terrainBuiltAt;
    @Nullable
    private TerrainBuilder<M> builder;

    protected RadarMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // --- Per machine ---

    protected abstract int energyCapacity();

    protected abstract int energyPerTick();

    /** The area's radius in blocks. */
    public abstract int radius();

    protected abstract int scanInterval();

    protected abstract int terrainRefreshInterval();

    protected abstract int terrainChunksPerTick();

    protected abstract TerrainBuilder<M> newTerrainBuilder(ServerLevel level, int radius);

    protected abstract CustomPacketPayload terrainPayload(M terrain);

    /** The contacts the viewer may see, sent every scan interval while they watch. */
    protected abstract CustomPacketPayload contactsPayload(ServerLevel level, ServerPlayer viewer);

    /** Called every tick after the energy, while powered. Nothing by default. */
    protected void tickPowered(ServerLevel level) {}

    /** Called on the tick the machine loses power. Nothing by default. */
    protected void onPowerLost(ServerLevel level) {}

    // --- State ---

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public boolean isPowered() {
        return powered;
    }

    /** The players who have the screen open. */
    public Set<ServerPlayer> viewers() {
        return viewers.keySet();
    }

    /** Whether the drone's horizontal position is inside the area (at any height). */
    public boolean inArea(double x, double z) {
        Vec3 center = Vec3.atCenterOf(worldPosition);
        double dx = x - center.x;
        double dz = z - center.z;
        double radius = radius();
        return dx * dx + dz * dz <= radius * radius;
    }

    /** The scan ticks, staggered by position so machines don't all scan on the same tick. */
    protected boolean isScanTick(ServerLevel level) {
        return Math.floorMod(level.getGameTime() + worldPosition.hashCode(), scanInterval()) == 0;
    }

    // --- Viewers ---

    /** Called when the player opens the screen. Data starts on the next tick, after the screen has opened on their side. */
    protected void addViewer(ServerPlayer player) {
        viewers.put(player, true);
    }

    void removeViewer(ServerPlayer player) {
        viewers.remove(player);
    }

    // --- Processing ---

    public final void serverTick(ServerLevel level) {
        boolean wasPowered = powered;
        setPowered(level, energy.consume(energyPerTick()));
        if (powered) {
            tickPowered(level);
        } else if (wasPowered) {
            onPowerLost(level);
        }
        viewers.keySet().removeIf(player -> player.isRemoved()
                || !(player.containerMenu instanceof RadarMachineMenu menu && menu.getPos().equals(worldPosition)));
        if (!powered || viewers.isEmpty()) {
            return;
        }
        updateTerrain(level);
        boolean scanTick = isScanTick(level);
        for (Map.Entry<ServerPlayer, Boolean> viewer : viewers.entrySet()) {
            boolean isNew = viewer.getValue();
            if (isNew && terrain != null) {
                PacketDistributor.sendToPlayer(viewer.getKey(), terrainPayload(terrain));
                viewer.setValue(false);
            }
            if (scanTick || isNew) {
                PacketDistributor.sendToPlayer(viewer.getKey(), contactsPayload(level, viewer.getKey()));
            }
        }
    }

    /** Starts a rebuild when the map is missing or older than the refresh interval, and sends it to everyone once it's done. */
    private void updateTerrain(ServerLevel level) {
        int radius = radius();
        boolean stale = terrain == null || terrain.radius() != radius
                || level.getGameTime() - terrainBuiltAt >= terrainRefreshInterval();
        if (builder == null && stale) {
            builder = newTerrainBuilder(level, radius);
        }
        if (builder == null) {
            return;
        }
        M built = builder.tick(level, terrainChunksPerTick());
        if (built != null) {
            builder = null;
            terrain = built;
            terrainBuiltAt = level.getGameTime();
            viewers.replaceAll((player, needsMap) -> true);
        }
    }

    /** Only writes the block state when it changes (section 7.6). */
    private void setPowered(Level level, boolean powered) {
        this.powered = powered;
        BlockState state = getBlockState();
        if (state.getValue(TwoBlockMachineBlock.POWERED) != powered) {
            level.setBlock(worldPosition, state.setValue(TwoBlockMachineBlock.POWERED, powered), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Marks the chunk for saving when the FE changes, without {@link #setChanged()}'s comparator update: that would
     * update the Uplink's neighbors every tick of FE use, while its alarm should only update them when the count
     * changes (section 7.9).
     */
    private void markEnergyChanged() {
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
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
