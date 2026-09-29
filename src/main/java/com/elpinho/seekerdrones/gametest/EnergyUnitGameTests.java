package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.energy.MekanismEnergy;
import com.elpinho.seekerdrones.network.EnergyUnitPayload;
import com.elpinho.seekerdrones.registry.ModAttachments;
import com.mojang.authlib.GameProfile;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * GameTests for the energy display unit (DESIGN.md section 5.4): the player attachment, its persistence, the payload
 * handler, the formatter and the {@code /seekerdrones energy get} output. Mekanism-specific assertions only run when
 * Mekanism is loaded, so the same tests pass with and without it.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class EnergyUnitGameTests {

    // --- Attachment ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyUnitAttachmentDefaultsToAuto(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        EnergyUnit unit = player.getData(ModAttachments.ENERGY_UNIT);
        helper.assertTrue(unit == EnergyUnit.AUTO, "Default unit should be AUTO, was " + unit);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyUnitSurvivesPlayerSaveAndLoad(GameTestHelper helper) {
        for (EnergyUnit unit : EnergyUnit.values()) {
            Player original = helper.makeMockPlayer(GameType.SURVIVAL);
            original.setData(ModAttachments.ENERGY_UNIT, unit);
            CompoundTag tag = new CompoundTag();
            original.saveWithoutId(tag);

            Player loaded = helper.makeMockPlayer(GameType.SURVIVAL);
            loaded.load(tag);
            EnergyUnit result = loaded.getData(ModAttachments.ENERGY_UNIT);
            helper.assertTrue(result == unit, "Unit " + unit + " should survive save/load, got " + result + " (tag=" + tag + ")");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyUnitIsCopiedOnDeathClone(GameTestHelper helper) {
        Player original = helper.makeMockPlayer(GameType.SURVIVAL);
        Player respawned = helper.makeMockPlayer(GameType.SURVIVAL);
        original.setData(ModAttachments.ENERGY_UNIT, EnergyUnit.JOULES);
        helper.assertTrue(respawned.getData(ModAttachments.ENERGY_UNIT) == EnergyUnit.AUTO, "Fresh player should start at AUTO");

        // The same event PlayerList.respawn posts; NeoForge's attachment internals copy the copyOnDeath attachments from it.
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, original, true));

        EnergyUnit result = respawned.getData(ModAttachments.ENERGY_UNIT);
        helper.assertTrue(result == EnergyUnit.JOULES, "Unit should be kept through death, got " + result);
        helper.succeed();
    }

    // --- Payload ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyUnitPayloadHandlerSetsUnitOnServerPlayer(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ServerPlayer player = spawnCapturingPlayer(helper, UUID.randomUUID(), new ArrayList<>());
        {
            IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(EnergyUnitGameTests.class.getClassLoader(),
                    new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("player")) {
                            return player;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            helper.assertTrue(player.getData(ModAttachments.ENERGY_UNIT) == EnergyUnit.AUTO, "Player should start at AUTO");
            EnergyUnitPayload.handleOnServer(new EnergyUnitPayload(EnergyUnit.FE), context);
            EnergyUnit result = player.getData(ModAttachments.ENERGY_UNIT);
            helper.assertTrue(result == EnergyUnit.FE, "Payload should set FE on the player, got " + result);
        }
        helper.succeed();
    }

    // --- Formatter ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyFormatFeShortAndExactForms(GameTestHelper helper) {
        assertEquals(helper, "45.2 kFE / 50 kFE", EnergyFormat.ratio(45210, 50000, EnergyUnit.FE));
        assertEquals(helper, "950 FE / 1.25 kFE", EnergyFormat.ratio(950, 1250, EnergyUnit.FE));
        assertEquals(helper, "45,210 / 50,000 FE", EnergyFormat.exactRatio(45210, 50000, EnergyUnit.FE));
        assertEquals(helper, "950 FE", EnergyFormat.amount(950, EnergyUnit.FE));
        assertEquals(helper, "1 MFE", EnergyFormat.amount(1_000_000, EnergyUnit.FE));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyFormatAutoAndJoulesResolveToFeWithoutMekanism(GameTestHelper helper) {
        if (MekanismEnergy.isLoaded()) {
            helper.succeed();
            return;
        }
        for (EnergyUnit unit : new EnergyUnit[] {EnergyUnit.AUTO, EnergyUnit.JOULES}) {
            helper.assertTrue(unit.resolve() == EnergyUnit.FE, unit + " should resolve to FE without Mekanism, got " + unit.resolve());
            assertEquals(helper, "45.2 kFE / 50 kFE", EnergyFormat.ratio(45210, 50000, unit));
            assertEquals(helper, "45,210 / 50,000 FE", EnergyFormat.exactRatio(45210, 50000, unit));
            assertEquals(helper, "950 FE", EnergyFormat.amount(950, unit));
            assertEquals(helper, "1 MFE", EnergyFormat.amount(1_000_000, unit));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyFormatMekanismJoules(GameTestHelper helper) {
        if (!MekanismEnergy.isLoaded()) {
            helper.succeed();
            return;
        }
        helper.assertTrue(MekanismEnergy.joulesPerFe() == 2.5, "joulesPerFe should be Mekanism's default 2.5, was " + MekanismEnergy.joulesPerFe());
        helper.assertTrue(EnergyUnit.AUTO.resolve() == EnergyUnit.JOULES, "AUTO should resolve to JOULES, got " + EnergyUnit.AUTO.resolve());
        assertEquals(helper, "2.5 kJ", EnergyFormat.amount(1000, EnergyUnit.AUTO));
        assertEquals(helper, "2.5 kJ", EnergyFormat.amount(1000, EnergyUnit.JOULES));
        assertEquals(helper, "1 kFE", EnergyFormat.amount(1000, EnergyUnit.FE));
        helper.succeed();
    }

    // --- Command ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void energyGetCommandPrintsExactValuesInPlayerUnit(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        List<Component> received = Collections.synchronizedList(new ArrayList<>());
        ServerPlayer player = spawnCapturingPlayer(helper, UUID.randomUUID(), received);
        {
            player.setData(ModAttachments.ENERGY_UNIT, EnergyUnit.FE);
            DroneData data = DroneData.createNew().withEnergy(45_210);
            player.setItemInHand(InteractionHand.MAIN_HAND, DroneItem.createStack(data));
            int max = DroneStats.maxEnergy(data);
            String expected = EnergyFormat.exactRatio(45_210, max, EnergyUnit.FE);
            helper.assertTrue(expected.startsWith("45,210 / ") && expected.endsWith(" FE"), "Sanity check of expected string: " + expected);

            CommandSourceStack source = player.createCommandSourceStack().withPermission(4);
            server.getCommands().performPrefixedCommand(source, "seekerdrones energy get");

            List<String> args = new ArrayList<>();
            synchronized (received) {
                for (Component message : received) {
                    if (message.getContents() instanceof TranslatableContents tc && tc.getKey().equals("commands.seekerdrones.energy.get")) {
                        args.add(String.valueOf(tc.getArgs()[1]));
                    }
                }
            }
            helper.assertTrue(args.equals(List.of(expected)), "energy get should print exact FE values " + expected + ", got " + args);
        }
        helper.succeed();
    }

    // --- Helpers ---

    private static void assertEquals(GameTestHelper helper, String expected, String actual) {
        helper.assertTrue(expected.equals(actual), "Expected \"" + expected + "\" but got \"" + actual + "\"");
    }

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    /** A real ServerPlayer (not online) whose system messages are captured (same shape as UpgradeGameTests). */
    private static ServerPlayer spawnCapturingPlayer(GameTestHelper helper, UUID uuid, List<Component> received) {
        MinecraftServer server = server(helper);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, "energy-unit-test-" + uuid.toString().substring(0, 8)), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            public void sendSystemMessage(Component message) {
                received.add(message);
            }
        };
        // Deliberately not added to the player list: placeNewPlayer would fire the login EnergyUnitPayload over a fake
        // connection that hasn't negotiated the payload channel, which throws.
        return player;
    }
}
