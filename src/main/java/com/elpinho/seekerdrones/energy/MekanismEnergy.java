package com.elpinho.seekerdrones.energy;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModList;

/**
 * Mekanism's FE to Joules conversion rate, for showing energy in Joules (DESIGN.md section 5.4). Mekanism isn't on the
 * compile classpath (it's optional), so its API is reached through reflection.
 */
public final class MekanismEnergy {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Mekanism's default rate, used when its configured rate can't be read. */
    private static final double DEFAULT_JOULES_PER_FE = 2.5;

    private static boolean resolved;
    @Nullable
    private static Object feConversion;
    @Nullable
    private static Method getConversion;
    private static boolean warned;

    private MekanismEnergy() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded("mekanism");
    }

    /** Joules per FE: Mekanism's configured rate, or its default when it can't be read (e.g. its config isn't loaded yet). */
    public static double joulesPerFe() {
        if (!resolved) {
            resolve();
        }
        if (feConversion != null && getConversion != null) {
            try {
                double rate = (double) getConversion.invoke(feConversion);
                if (rate > 0) {
                    return rate;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                warnOnce(e);
            }
        }
        return DEFAULT_JOULES_PER_FE;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!isLoaded()) {
            return;
        }
        try {
            // IEnergyConversionHelper.INSTANCE.feConversion().getConversion(): Joules = FE * conversion.
            Class<?> helper = Class.forName("mekanism.api.energy.IEnergyConversionHelper");
            Object instance = helper.getField("INSTANCE").get(null);
            feConversion = helper.getMethod("feConversion").invoke(instance);
            getConversion = Class.forName("mekanism.api.energy.IEnergyConversion").getMethod("getConversion");
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    private static void warnOnce(Exception e) {
        if (!warned) {
            warned = true;
            LOGGER.warn("Couldn't read Mekanism's FE conversion rate, using {} J/FE", DEFAULT_JOULES_PER_FE, e);
        }
    }
}
