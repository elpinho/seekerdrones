package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.energy.EnergyUnit;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, SeekerDrones.MODID);

    /** The unit a player sees energy in (DESIGN.md section 5.4). Saved with the player and kept through death. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<EnergyUnit>> ENERGY_UNIT = ATTACHMENT_TYPES.register(
            "energy_unit",
            () -> AttachmentType.builder(() -> EnergyUnit.AUTO)
                    .serialize(EnergyUnit.CODEC)
                    .copyOnDeath()
                    .build());
}
