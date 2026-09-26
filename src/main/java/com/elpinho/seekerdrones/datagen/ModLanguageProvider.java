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
    }
}
