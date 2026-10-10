package com.elpinho.seekerdrones.compat.jade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.deploying.DeployingStatus;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.factory.FactoryStatus;
import com.elpinho.seekerdrones.programming.DroneProgram;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;

/**
 * Each machine's mode and status lines. Following Jade's conventions, normal work is left to the progress bar
 * ({@link MachineProgressProvider}) and the energy, tank and inventory displays, and only modes and problems get a
 * line. The lines are built on the server, where the machine state is.
 */
public enum MachineProvider implements IBlockComponentProvider, StreamServerDataProvider<BlockAccessor, List<MachineProvider.Line>> {
    INSTANCE;

    private static final ResourceLocation UID = SeekerDronesJadePlugin.id("machine");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public List<Line> streamData(BlockAccessor accessor) {
        List<Line> lines = new ArrayList<>();
        switch (accessor.getBlockEntity()) {
            case DroneFactoryBlockEntity factory -> factoryLines(factory, lines);
            case ProgrammingStationBlockEntity station -> programmingLines(station, lines);
            case DeployingStationBlockEntity station -> deployingLines(station, lines);
            case ChargingStationBlockEntity station -> chargingLines(accessor, station, lines);
            case null, default -> {
                return null;
            }
        }
        return lines;
    }

    private static void factoryLines(DroneFactoryBlockEntity factory, List<Line> lines) {
        FactoryStatus status = factory.getStatus();
        if (status == FactoryStatus.NO_ENERGY || status == FactoryStatus.MISSING_FLUID || status == FactoryStatus.OUTPUT_FULL) {
            lines.add(new Line(Component.translatable(status.getTranslationKey()), Severity.WARNING));
        }
    }

    private static void programmingLines(ProgrammingStationBlockEntity station, List<Line> lines) {
        ProgrammingMode mode = station.getMode();
        lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.mode",
                Component.translatable(mode.getTranslationKey())), Severity.NORMAL));
        UpgradeType installing = station.getInstalling();
        if (installing != null) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.installing",
                    Component.translatable(installing.getTranslationKey())), Severity.NORMAL));
            return;
        }
        if (station.isCharging()) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.charging"), Severity.NORMAL));
            return;
        }
        Optional<DroneData> drone = station.getDrone();
        if (mode != ProgrammingMode.TEMPLATE) {
            return;
        }
        if (!station.isTemplateValid()) {
            lines.add(new Line(Component.translatable("screen.seekerdrones.programming_station.status.template_invalid"), Severity.DANGER));
        } else if (drone.isEmpty()) {
            return;
        } else if (!station.isAccepted()) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.reinsert"), Severity.WARNING));
        } else if (station.isComplete()) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.complete"), Severity.SUCCESS));
        } else if (hasExtraUpgrades(drone.get(), station.getTemplate())) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.extra_upgrades"), Severity.DANGER));
        } else if (station.getTemplate().matches(drone.get())) {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.no_power_charge"), Severity.WARNING));
        } else {
            lines.add(new Line(Component.translatable("jade.seekerdrones.programming_station.waiting"), Severity.WARNING));
        }
    }

    private static boolean hasExtraUpgrades(DroneData drone, DroneProgram template) {
        for (UpgradeType type : UpgradeType.values()) {
            if (drone.upgradeCount(type) > template.upgradeCount(type)) {
                return true;
            }
        }
        return false;
    }

    private static void deployingLines(DeployingStationBlockEntity station, List<Line> lines) {
        lines.add(new Line(Component.translatable(station.isAutoDeploy()
                ? "jade.seekerdrones.deploying_station.auto_deploy_on"
                : "tooltip.seekerdrones.deploying_station.auto_deploy_off"), Severity.NORMAL));
        DeployingStatus status = station.getStatus();
        if (status == DeployingStatus.NO_ENERGY || status == DeployingStatus.BLOCKED) {
            lines.add(new Line(Component.translatable(status.getTranslationKey()), Severity.WARNING));
        }
    }

    private static void chargingLines(BlockAccessor accessor, ChargingStationBlockEntity station, List<Line> lines) {
        if (!(accessor.getLevel() instanceof ServerLevel level)) {
            return;
        }
        StationStatusPayload status = StationStatusPayload.of(level, station, false);
        status.drone().ifPresent(drone -> {
            String name = drone.data().config().label();
            Component label = Component.literal(name.isEmpty() ? drone.data().droneId() : name);
            Component state = Component.translatable(status.status().getTranslationKey());
            lines.add(new Line(Component.translatable("jade.seekerdrones.charging_station.drone", state, label),
                    status.status() == StationStatusPayload.Status.NO_POWER ? Severity.WARNING : Severity.NORMAL));
        });
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, List<Line>> streamCodec() {
        return Line.STREAM_CODEC.apply(ByteBufCodecs.list());
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        decodeFromData(accessor).ifPresent(lines -> lines.forEach(line -> tooltip.add(line.styled())));
    }

    public enum Severity {
        NORMAL, SUCCESS, WARNING, DANGER;

        static final StreamCodec<ByteBuf, Severity> STREAM_CODEC = ByteBufCodecs.idMapper(id -> values()[id], Enum::ordinal);
    }

    public record Line(Component text, Severity severity) {
        static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Line::text,
                Severity.STREAM_CODEC, Line::severity,
                Line::new);

        /** The text in the Jade theme's color for its severity. */
        Component styled() {
            IThemeHelper theme = IThemeHelper.get();
            return switch (severity) {
                case NORMAL -> text;
                case SUCCESS -> theme.success(text);
                case WARNING -> theme.warning(text);
                case DANGER -> theme.danger(text);
            };
        }
    }
}
