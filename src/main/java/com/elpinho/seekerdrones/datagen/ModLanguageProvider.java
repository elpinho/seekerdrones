package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.deploying.DeployingStatus;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.registry.ModBlocks;
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
        addBlock(ModBlocks.CHARGING_STATION, "Drone Charging Station");
        addBlock(ModBlocks.DRONE_FACTORY, "Drone Factory");
        addBlock(ModBlocks.PROGRAMMING_STATION, "Drone Programming Station");
        addBlock(ModBlocks.DEPLOYING_STATION, "Drone Deploying Station");
        add("tooltip.seekerdrones.deploying_station.auto_deploy_off", "Auto-deploy: Off");
        add("screen.seekerdrones.deploying_station.auto_deploy.on", "Auto-deploy: On");
        add("screen.seekerdrones.deploying_station.auto_deploy.off", "Auto-deploy: Off");
        add("screen.seekerdrones.deploying_station.auto_deploy.tooltip", "On: deploy every inserted drone as soon as possible.\nOff: deploy with the Deploy button or a redstone pulse.");
        add("screen.seekerdrones.deploying_station.deploy", "Deploy");
        add("screen.seekerdrones.deploying_station.energy_per_deploy", "%s per deploy");
        add("screen.seekerdrones.deploying_station.status", "Status: %s");
        add(DeployingStatus.IDLE.getTranslationKey(), "No drone");
        add(DeployingStatus.READY.getTranslationKey(), "Ready");
        add(DeployingStatus.NO_ENERGY.getTranslationKey(), "Not enough energy");
        add(DeployingStatus.BLOCKED.getTranslationKey(), "Space above blocked");
        add("tooltip.seekerdrones.programming_station.programmed", "Programmed (%s)");
        add("screen.seekerdrones.programming_station.mode.direct", "Mode: Direct");
        add("screen.seekerdrones.programming_station.mode.template", "Mode: Template");
        add("screen.seekerdrones.programming_station.mode.tooltip", "Direct: edit the drone in the slot by hand.\nTemplate: bring every inserted drone to the stored template. Automation can pull complete drones out.");
        add("screen.seekerdrones.programming_station.tab.upgrades", "Upgrades");
        add("screen.seekerdrones.programming_station.tab.targets", "Targets");
        add("screen.seekerdrones.programming_station.tab.settings", "Settings");
        add("screen.seekerdrones.programming_station.no_drone", "Insert a drone to edit it.");
        add("screen.seekerdrones.programming_station.loading", "Loading...");
        add("screen.seekerdrones.programming_station.slots", "Slots: %s / %s");
        add("screen.seekerdrones.programming_station.copy_from_drone", "Copy from drone");
        add("screen.seekerdrones.programming_station.copy_from_drone.tooltip", "Set the template to the drone's upgrades and settings");
        add("screen.seekerdrones.programming_station.upgrade.install", "Install one from the input");
        add("screen.seekerdrones.programming_station.upgrade.remove", "Remove one (it goes into your inventory)");
        add("screen.seekerdrones.programming_station.upgrade.remove_extra", "Remove one the template doesn't ask for (it goes into your inventory)");
        add("screen.seekerdrones.programming_station.upgrade.installed", "Installed: %s (max %s)");
        add("screen.seekerdrones.programming_station.upgrade.programmed", "Template: %s");
        add("screen.seekerdrones.programming_station.upgrade.in_input", "In input: %s");
        add("screen.seekerdrones.programming_station.upgrade.cost", "Next install: %s");
        add("screen.seekerdrones.programming_station.target.header", "Targets: %s (%s slots)");
        add("screen.seekerdrones.programming_station.target.value", "Target");
        add("screen.seekerdrones.programming_station.target.kind.tooltip", "Entity type, entity tag or player name");
        add("screen.seekerdrones.programming_station.target.remove", "Remove this target");
        add("screen.seekerdrones.programming_station.target.duplicate", "%s is already a target");
        add("screen.seekerdrones.programming_station.target.unknown.entity_type", "Unknown entity type: %s");
        add("screen.seekerdrones.programming_station.target.unknown.tag", "Unknown entity tag: %s");
        add("screen.seekerdrones.programming_station.target.unknown.player_name", "Invalid player name: %s");
        add("screen.seekerdrones.programming_station.target.no_slot", "No free target slot (needs more Multi-target)");
        add("screen.seekerdrones.programming_station.target.needs_player_seek", "Player targets need Player Seek");
        add("screen.seekerdrones.programming_station.target.ignored.no_slot", "Ignored: no free target slot");
        add("screen.seekerdrones.programming_station.target.ignored.player_seek", "Ignored: needs Player Seek");
        add("screen.seekerdrones.programming_station.target.blacklisted", "%s is blacklisted on this server");
        add("screen.seekerdrones.programming_station.target.ignored.blacklisted", "Ignored: blacklisted on this server");
        add("screen.seekerdrones.programming_station.follow_distance", "Follow distance");
        add("screen.seekerdrones.programming_station.follow_distance.tooltip", "Blocks kept from the target while following (%s-%s)");
        add("screen.seekerdrones.programming_station.follow_distance.invalid", "The follow distance must be %s-%s");
        add("screen.seekerdrones.programming_station.label", "Label");
        add("screen.seekerdrones.programming_station.label.hint", "None");
        add("screen.seekerdrones.programming_station.color", "Color");
        add("screen.seekerdrones.programming_station.color.tooltip", "Left-click: next color. Right-click: previous color.");
        add("screen.seekerdrones.programming_station.patrol_center", "Patrol center");
        add("screen.seekerdrones.programming_station.patrol_center.here", "Here");
        add("screen.seekerdrones.programming_station.patrol_center.here.tooltip", "Use the block above this station");
        add("screen.seekerdrones.programming_station.patrol_center.clear", "Clear");
        add("screen.seekerdrones.programming_station.patrol_center.clear.tooltip", "No patrol center: the drone patrols around where it came to rest");
        add("screen.seekerdrones.programming_station.patrol_radius", "Patrol radius");
        add("screen.seekerdrones.programming_station.patrol_radius.tooltip", "Empty: the largest the Patrol upgrades allow (%s)");
        add("screen.seekerdrones.programming_station.patrol_radius.invalid", "The patrol radius must be at least 1");
        add("screen.seekerdrones.programming_station.status.insert_drone", "Insert a drone to edit it.");
        add("screen.seekerdrones.programming_station.status.insert_drone_template", "Insert a drone to program it.");
        add("screen.seekerdrones.programming_station.status.direct_hint", "Put upgrades in the input and press + to install them.");
        add("screen.seekerdrones.programming_station.status.installing", "Installing %s: %s%% (%s)");
        add("screen.seekerdrones.programming_station.status.no_power", "Not enough energy to install %s");
        add("screen.seekerdrones.programming_station.status.template_invalid", "The template exceeds the upgrade limits");
        add("screen.seekerdrones.programming_station.status.extra_upgrades", "Remove extra upgrades: %s");
        add("screen.seekerdrones.programming_station.status.missing_upgrades", "Waiting for upgrades: %s");
        add("screen.seekerdrones.programming_station.status.complete", "Complete: ready to be taken out");
        add("screen.seekerdrones.programming_station.status.reinsert_drone", "This drone was here before Template mode. Re-insert it to program it.");
        add("screen.seekerdrones.programming_station.status.working", "Programming...");
        addItem(ModItems.DRONE_ROTOR, "Drone Rotor");
        addItem(ModItems.SEEKER_CORE, "Seeker Core");

        add("tooltip.seekerdrones.drone.unassigned", "Unassigned");
        add("tooltip.seekerdrones.drone.owner", "Owner: %s");
        add("tooltip.seekerdrones.drone.energy", "Energy: %s");
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

        addItem(ModItems.upgrade(UpgradeType.PATROL), "Patrol Upgrade");
        addItem(ModItems.upgrade(UpgradeType.SIGHT), "Sight Upgrade");
        addItem(ModItems.upgrade(UpgradeType.EXPLOSIVE), "Explosive Upgrade");
        addItem(ModItems.upgrade(UpgradeType.SIREN), "Siren Upgrade");
        addItem(ModItems.upgrade(UpgradeType.TRANSMITTER), "Transmitter Upgrade");
        addItem(ModItems.upgrade(UpgradeType.ENERGY), "Energy Upgrade");
        addItem(ModItems.upgrade(UpgradeType.HEALTH), "Health Upgrade");
        addItem(ModItems.upgrade(UpgradeType.PLAYER_SEEK), "Player Seek Upgrade");
        addItem(ModItems.upgrade(UpgradeType.MULTI_TARGET), "Multi-target Upgrade");
        addItem(ModItems.upgrade(UpgradeType.XRAY), "X-ray Upgrade");

        add(UpgradeType.PATROL.getTranslationKey() + ".description", "Patrols in a circle around its patrol center. More upgrades widen the circle.");
        add(UpgradeType.SIGHT.getTranslationKey() + ".description", "Increases the range at which targets are spotted.");
        add(UpgradeType.EXPLOSIVE.getTranslationKey() + ".description", "Explodes on reaching its target. More upgrades make a bigger explosion.");
        add(UpgradeType.SIREN.getTranslationKey() + ".description", "Sounds a siren when a target is spotted. More upgrades make it louder.");
        add(UpgradeType.TRANSMITTER.getTranslationKey() + ".description", "Tells the drone's online operators when a target is spotted.");
        add(UpgradeType.ENERGY.getTranslationKey() + ".description", "Increases max energy.");
        add(UpgradeType.HEALTH.getTranslationKey() + ".description", "Increases max health.");
        add(UpgradeType.PLAYER_SEEK.getTranslationKey() + ".description", "Allows players as targets. The drone's operators are never targeted.");
        add(UpgradeType.MULTI_TARGET.getTranslationKey() + ".description", "Adds target slots.");
        add(UpgradeType.XRAY.getTranslationKey() + ".description", "Spots and tracks targets through walls.");
        add("tooltip.seekerdrones.upgrade.max_count", "Max per drone: %s");

        add("subtitles.seekerdrones.drone_siren", "Drone siren blares");
        add("message.seekerdrones.transmitter", "[%s] spotted %s at %s, %s, %s");

        add(DroneState.IDLE.getTranslationKey(), "Idle");
        add(DroneState.PATROLLING.getTranslationKey(), "Patrolling");
        add(DroneState.CHASING.getTranslationKey(), "Chasing");
        add(DroneState.FOLLOWING.getTranslationKey(), "Following");
        add(DroneState.RETURNING.getTranslationKey(), "Returning to charge");
        add(DroneState.CHARGING.getTranslationKey(), "Charging");

        add("screen.seekerdrones.drone_status", "Drone Status");
        add("screen.seekerdrones.drone_status.state", "State:");
        add("screen.seekerdrones.drone_status.energy", "Energy:");
        add("screen.seekerdrones.drone_status.health", "Health:");
        add("screen.seekerdrones.drone_status.health_value", "%s / %s");
        add("screen.seekerdrones.drone_status.sight_range", "Sight range:");
        add("screen.seekerdrones.drone_status.sight_range_value", "%s blocks");
        add("screen.seekerdrones.drone_status.patrol_center", "Patrol center:");
        add("screen.seekerdrones.drone_status.upgrades", "Upgrades:");
        add("screen.seekerdrones.drone_status.targets", "Targets:");
        add("screen.seekerdrones.drone_status.none", "None");
        add("screen.seekerdrones.drone_status.not_set", "Not set");
        add("screen.seekerdrones.drone_status.not_deployed", "Not deployed");

        add("screen.seekerdrones.charging_station", "Drone Charging Station");
        add("screen.seekerdrones.charging_station.status", "Status:");
        add("screen.seekerdrones.charging_station.repair_fluid", "Repair fluid:");
        add("screen.seekerdrones.charging_station.repair_fluid_value", "%s %s / %s mB");

        add("screen.seekerdrones.drone_factory.operators", "Operators");
        add("screen.seekerdrones.drone_factory.empty", "Empty");
        add("screen.seekerdrones.drone_factory.fluid_value", "%s / %s mB");
        add("screen.seekerdrones.drone_factory.progress_value", "%s%%");
        add("screen.seekerdrones.drone_factory.operators.title", "Drone Factory Operators");
        add("screen.seekerdrones.drone_factory.operators.none", "No operators besides you.");
        add("screen.seekerdrones.drone_factory.operators.name", "Player name");
        add("screen.seekerdrones.drone_factory.operators.add", "Add");
        add("screen.seekerdrones.drone_factory.operators.remove", "Remove");
        add("screen.seekerdrones.drone_factory.operators.added", "Added %s.");
        add("screen.seekerdrones.drone_factory.operators.removed", "Removed %s.");
        add("screen.seekerdrones.drone_factory.operators.unknown_player", "Unknown player: %s");
        add("screen.seekerdrones.drone_factory.operators.already_operator", "%s is already an operator.");
        add("screen.seekerdrones.drone_factory.operators.not_operator", "%s is not an operator.");
        add("jei.seekerdrones.drone_assembly", "Drone Assembly");
        add("jei.seekerdrones.drone_assembly.time", "%s s");
        add("screen.seekerdrones.charging_station.energy", "Energy:");
        add("screen.seekerdrones.charging_station.charge_rate", "Charge rate:");
        add("screen.seekerdrones.charging_station.owner", "Owner:");
        add("screen.seekerdrones.charging_station.drone", "Drone:");
        add("screen.seekerdrones.charging_station.drone_energy", "Energy:");
        add("screen.seekerdrones.charging_station.drone_health", "Health:");
        add(StationStatusPayload.Status.IDLE.getTranslationKey(), "Idle");
        add(StationStatusPayload.Status.DOCKING.getTranslationKey(), "Drone docking");
        add(StationStatusPayload.Status.CHARGING.getTranslationKey(), "Charging");
        add(StationStatusPayload.Status.HEALING.getTranslationKey(), "Repairing");
        add(StationStatusPayload.Status.NO_POWER.getTranslationKey(), "Out of power");

        add(EnergyUnit.AUTO.getTranslationKey(), "Auto");
        add(EnergyUnit.FE.getTranslationKey(), "FE");
        add(EnergyUnit.JOULES.getTranslationKey(), "J");
        add(EnergyUnit.AUTO.getTranslationKey() + ".name", "Auto (Mekanism Joules)");
        add(EnergyUnit.FE.getTranslationKey() + ".name", "Forge Energy (FE)");
        add(EnergyUnit.JOULES.getTranslationKey() + ".name", "Mekanism Joules (J)");
        add("screen.seekerdrones.energy_unit.tooltip", "Energy unit: %s\nClick to change. Saved for you in this world.");

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
        add("commands.seekerdrones.config.blacklisted_target", "%s is blacklisted on this server");
        add("commands.seekerdrones.config.no_such_index", "There is no target #%s (the drone has %s)");
        add("commands.seekerdrones.config.target.added", "Added target %s to %s drone(s)");
        add("commands.seekerdrones.config.target.removed", "Removed target #%s from %s drone(s)");
        add("commands.seekerdrones.config.target.cleared", "Cleared the targets of %s drone(s)");
        add("commands.seekerdrones.config.target.list.header", "%s - %s target(s), %s slot(s), follow distance %s:");
        add("commands.seekerdrones.config.target.list.empty", "  No targets");
        add("commands.seekerdrones.config.target.list.entry", "  %s. %s %s");
        add("commands.seekerdrones.config.target.list.ignored_slot", " (ignored: no free target slot)");
        add("commands.seekerdrones.config.target.list.ignored_player_seek", " (ignored: needs Player Seek)");
        add("commands.seekerdrones.config.target.list.ignored_blacklisted", " (ignored: blacklisted)");
        add("commands.seekerdrones.config.target.kind.entity_type", "Entity");
        add("commands.seekerdrones.config.target.kind.tag", "Tag");
        add("commands.seekerdrones.config.target.kind.player_name", "Player");
        add("commands.seekerdrones.config.followdistance.set", "Set the follow distance of %s drone(s) to %s");
        add("commands.seekerdrones.config.followdistance.too_large", "The follow distance can be at most %s");
        add("commands.seekerdrones.config.patrolcenter.set", "Set the patrol center of %s drone(s) to %s, %s, %s in %s");
        add("commands.seekerdrones.config.patrolcenter.cleared", "Cleared the patrol center of %s drone(s)");
        add("commands.seekerdrones.config.patrolradius.set", "Set the patrol radius of %s drone(s) to %s (capped by their Patrol upgrades)");
        add("commands.seekerdrones.config.patrolradius.cleared", "%s drone(s) now patrol at the largest radius their Patrol upgrades allow");
        add("commands.seekerdrones.config.unknown_color", "Unknown color %s");
        add("commands.seekerdrones.config.color.set", "Set the color of %s drone(s) to %s");
        add("screen.seekerdrones.drone_status.patrol_radius", "Patrol radius:");
        add("screen.seekerdrones.drone_status.patrol_radius_value", "%s (max %s)");

        add("commands.seekerdrones.upgrade.unknown_type", "Unknown upgrade type %s");
        add("commands.seekerdrones.upgrade.over_type_cap", "A drone can have at most %2$s %1$s upgrade(s)");
        add("commands.seekerdrones.upgrade.over_total_slots", "That would use %s upgrade slots, but a drone has %s");
        add("commands.seekerdrones.upgrade.set", "Set %s upgrades to %s on %s drone(s)");
        add("commands.seekerdrones.upgrade.cleared", "Removed all upgrades from %s drone(s)");
        add("commands.seekerdrones.upgrade.list.header", "%s - %s / %s upgrade slots used:");
        add("commands.seekerdrones.upgrade.list.empty", "  No upgrades");
        add("commands.seekerdrones.upgrade.list.entry", "  %s x%s (max %s)");

        add("commands.seekerdrones.energy.set", "Set the energy of %s drone(s)");
        add("commands.seekerdrones.energy.get", "%s - %s");
    }
}
