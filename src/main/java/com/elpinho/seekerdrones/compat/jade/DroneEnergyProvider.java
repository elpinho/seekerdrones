package com.elpinho.seekerdrones.compat.jade;

import java.util.List;
import java.util.Objects;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.Accessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/**
 * A drone's energy as Jade's standard energy bar. Drones expose no energy capability (nothing may charge or drain
 * them from outside), so Jade can't find it on its own.
 */
public enum DroneEnergyProvider implements IServerExtensionProvider<CompoundTag>, IClientExtensionProvider<CompoundTag, EnergyView> {
    INSTANCE;

    private static final ResourceLocation UID = SeekerDronesJadePlugin.id("drone_energy");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public List<ViewGroup<CompoundTag>> getGroups(Accessor<?> accessor) {
        if (!(accessor instanceof EntityAccessor entityAccessor) || !(entityAccessor.getEntity() instanceof DroneEntity drone)) {
            return null;
        }
        DroneData data = drone.snapshotData();
        return List.of(new ViewGroup<>(List.of(EnergyView.of(data.energy(), DroneStats.maxEnergy(data)))));
    }

    @Override
    public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
        return groups.stream()
                .map(group -> new ClientViewGroup<>(group.views.stream()
                        .map(view -> EnergyView.read(view, "FE"))
                        .filter(Objects::nonNull)
                        .toList()))
                .toList();
    }
}
