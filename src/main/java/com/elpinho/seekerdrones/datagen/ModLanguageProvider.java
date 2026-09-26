package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModLanguageProvider extends LanguageProvider {
    public ModLanguageProvider(PackOutput output) {
        super(output, SeekerDrones.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.seekerdrones", "Seeker Drones");

        addItem(ModItems.DRONE, "Drone");
        addEntityType(ModEntityTypes.DRONE, "Drone");

        add("tooltip.seekerdrones.drone.unassigned", "Unassigned");
        add("tooltip.seekerdrones.drone.energy", "Energy: %s / %s FE");
        add("tooltip.seekerdrones.drone.health", "Health: %s / %s");
        add("tooltip.seekerdrones.drone.upgrades", "Upgrades:");
        add("tooltip.seekerdrones.drone.upgrade_entry", "%s x%s");
        add("tooltip.seekerdrones.drone.targets", "Targets: %s");

        add(UpgradeType.PATROL.getTranslationKey(), "Patrol");
        add(UpgradeType.SIGHT.getTranslationKey(), "Sight");
        add(UpgradeType.EXPLOSIVE.getTranslationKey(), "Explosive");
        add(UpgradeType.SIREN.getTranslationKey(), "Siren");
        add(UpgradeType.TRANSMITTER.getTranslationKey(), "Transmitter");
        add(UpgradeType.ENERGY.getTranslationKey(), "Energy");
        add(UpgradeType.HEALTH.getTranslationKey(), "Health");
        add(UpgradeType.PLAYER_SEEK.getTranslationKey(), "Player Seek");
        add(UpgradeType.MULTI_TARGET.getTranslationKey(), "Multi-target");
        add(UpgradeType.XRAY.getTranslationKey(), "X-ray");

        add(DroneState.IDLE.getTranslationKey(), "Idle");
        add(DroneState.PATROLLING.getTranslationKey(), "Patrolling");
        add(DroneState.CHASING.getTranslationKey(), "Chasing");
        add(DroneState.FOLLOWING.getTranslationKey(), "Following");
        add(DroneState.RETURNING.getTranslationKey(), "Returning to charge");
        add(DroneState.CHARGING.getTranslationKey(), "Charging");

        add("screen.seekerdrones.drone_status", "Drone Status");
        add("screen.seekerdrones.drone_status.state", "State:");
        add("screen.seekerdrones.drone_status.energy", "Energy:");
        add("screen.seekerdrones.drone_status.energy_value", "%s / %s FE");
        add("screen.seekerdrones.drone_status.health", "Health:");
        add("screen.seekerdrones.drone_status.health_value", "%s / %s");
        add("screen.seekerdrones.drone_status.patrol_center", "Patrol center:");
        add("screen.seekerdrones.drone_status.upgrades", "Upgrades:");
        add("screen.seekerdrones.drone_status.targets", "Targets:");
        add("screen.seekerdrones.drone_status.none", "None");
        add("screen.seekerdrones.drone_status.not_set", "Not set");
        add("screen.seekerdrones.drone_status.not_deployed", "Not deployed");

        add("message.seekerdrones.not_operator", "You are not an operator of this drone");

        add("commands.seekerdrones.not_holding_drone", "You must hold a drone item in your main hand");
        add("commands.seekerdrones.no_drones", "No drones matched");

        add("commands.seekerdrones.group.unknown", "Unknown operator group %s");
        add("commands.seekerdrones.group.single_owner", "The owner must be exactly one player");
        add("commands.seekerdrones.group.created", "Created operator group %s owned by %s");
        add("commands.seekerdrones.group.list.empty", "There are no operator groups");
        add("commands.seekerdrones.group.list.header", "%s operator group(s):");
        add("commands.seekerdrones.group.list.entry", "%s - owner %s, %s operator(s)");
        add("commands.seekerdrones.group.info", "Group %s - owner: %s, operators: %s");
        add("commands.seekerdrones.group.added", "Added %s operator(s) to group %s");
        add("commands.seekerdrones.group.removed", "Removed %s operator(s) from group %s");
        add("commands.seekerdrones.group.cannot_remove_owner", "%s is the group owner and can't be removed");
        add("commands.seekerdrones.group.assigned", "Assigned %s drone(s) to group %s");
        add("commands.seekerdrones.group.cleared", "Removed the operator group from %s drone(s)");

        add("commands.seekerdrones.config.unknown_tag", "Unknown entity tag #%s");
        add("commands.seekerdrones.config.duplicate_target", "%s is already a target");
        add("commands.seekerdrones.config.no_such_index", "There is no target #%s (the drone has %s)");
        add("commands.seekerdrones.config.target.added", "Added target %s to %s drone(s)");
        add("commands.seekerdrones.config.target.removed", "Removed target #%s from %s drone(s)");
        add("commands.seekerdrones.config.target.cleared", "Cleared the targets of %s drone(s)");
        add("commands.seekerdrones.config.target.list.header", "%s - %s target(s), %s slot(s), follow distance %s:");
        add("commands.seekerdrones.config.target.list.empty", "  No targets");
        add("commands.seekerdrones.config.target.list.entry", "  %s. %s %s");
        add("commands.seekerdrones.config.target.list.ignored_slot", " (ignored: no free target slot)");
        add("commands.seekerdrones.config.target.list.ignored_player_seek", " (ignored: needs Player Seek)");
        add("commands.seekerdrones.config.target.kind.entity_type", "Entity");
        add("commands.seekerdrones.config.target.kind.tag", "Tag");
        add("commands.seekerdrones.config.target.kind.player_name", "Player");
        add("commands.seekerdrones.config.followdistance.set", "Set the follow distance of %s drone(s) to %s");
    }
}
