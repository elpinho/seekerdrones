package com.elpinho.seekerdrones.compat.jade;

import java.util.List;

import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ProgressView;
import snownee.jade.api.view.ViewGroup;

/** Jade's standard progress bar for a drone build in the Factory and an install step in the Programming Station. */
public enum MachineProgressProvider implements IServerExtensionProvider<CompoundTag>, IClientExtensionProvider<CompoundTag, ProgressView> {
    INSTANCE;

    private static final ResourceLocation UID = SeekerDronesJadePlugin.id("machine_progress");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public List<ViewGroup<CompoundTag>> getGroups(Accessor<?> accessor) {
        float progress = switch (accessor.getTarget()) {
            case DroneFactoryBlockEntity factory -> factory.recipeTime() > 0 ? (float) factory.getProgress() / factory.recipeTime() : 0;
            case ProgrammingStationBlockEntity station -> station.getInstalling() != null
                    ? (float) station.getProgress() / ProgrammingStationBlockEntity.installTime() : 0;
            case null, default -> 0;
        };
        return progress > 0 ? List.of(new ViewGroup<>(List.of(ProgressView.create(progress)))) : null;
    }

    @Override
    public List<ClientViewGroup<ProgressView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
        return ClientViewGroup.map(groups, ProgressView::read, null);
    }
}
