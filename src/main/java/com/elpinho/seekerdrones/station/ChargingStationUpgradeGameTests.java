package com.elpinho.seekerdrones.station;

import java.util.UUID;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Charging Station Upgrades tab, server side (DESIGN.md sections 7.4 and 7.7). Lives in the station package to reach setOwner. */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ChargingStationUpgradeGameTests {

    private static final BlockPos REL = new BlockPos(4, 2, 4);

    private static ChargingStationBlockEntity station(GameTestHelper helper, UUID owner) {
        helper.setBlock(REL, ModBlocks.CHARGING_STATION.get());
        ChargingStationBlockEntity be = (ChargingStationBlockEntity) helper.getBlockEntity(REL);
        be.setOwner(owner);
        return be;
    }

    private static Player player(GameTestHelper helper, UUID uuid, int permissionLevel) {
        Player player = new Player(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(uuid, "station-test")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            protected int getPermissionLevel() {
                return permissionLevel;
            }
        };
        player.moveTo(helper.absoluteVec(REL.getCenter()));
        return player;
    }

    private static ItemStack energy(int count) {
        return new ItemStack(ModItems.upgrade(UpgradeType.ENERGY).get(), count);
    }

    private static int countEnergy(Player player) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(ModItems.upgrade(UpgradeType.ENERGY).get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    // --- 1. Access ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void stationOwnerCanManage(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        helper.assertTrue(ChargingStationAccess.canManage(player(helper, owner, 0), be), "Owner should manage");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void ownerOfAnotherStationCannotManage(GameTestHelper helper) {
        ChargingStationBlockEntity be = station(helper, UUID.randomUUID());
        helper.assertFalse(ChargingStationAccess.canManage(player(helper, UUID.randomUUID(), 0), be), "Other player should not manage");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void operatorOfOwnersGroupCanManage(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        OperatorGroups groups = OperatorGroups.get(helper.getLevel().getServer());
        UUID group = groups.createGroup(owner);
        groups.addOperator(group, op);
        ChargingStationBlockEntity be = station(helper, owner);
        helper.assertTrue(ChargingStationAccess.canManage(player(helper, op, 0), be), "Operator of owner's group should manage");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void memberOfGroupOwnedBySomeoneElseCannotManage(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        OperatorGroups groups = OperatorGroups.get(helper.getLevel().getServer());
        UUID group = groups.createGroup(other);
        groups.addOperator(group, member);
        ChargingStationBlockEntity be = station(helper, owner);
        helper.assertFalse(ChargingStationAccess.canManage(player(helper, member, 0), be), "Member of a foreign group should not manage");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void anyoneCanManageStationWithoutOwner(GameTestHelper helper) {
        ChargingStationBlockEntity be = station(helper, null);
        helper.assertTrue(ChargingStationAccess.canManage(player(helper, UUID.randomUUID(), 0), be), "Ownerless station is open");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void permissionLevelTwoCanManageAnyStation(GameTestHelper helper) {
        ChargingStationBlockEntity be = station(helper, UUID.randomUUID());
        helper.assertTrue(ChargingStationAccess.canManage(player(helper, UUID.randomUUID(), 2), be), "Level 2 should manage");
        helper.succeed();
    }

    // --- 2. Menu shape ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void managerMenuHasOneUpgradeSlotAndInventory(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        Player p = player(helper, owner, 0);
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
        helper.assertTrue(menu.hasInventory(), "hasInventory");
        helper.assertValueEqual(menu.getUpgradeSlots().size(), 1, "upgrade slots");
        helper.assertValueEqual(menu.slots.size(), 37, "total slots");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void nonManagerMenuHasNoSlots(GameTestHelper helper) {
        ChargingStationBlockEntity be = station(helper, UUID.randomUUID());
        Player p = player(helper, UUID.randomUUID(), 0);
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, false);
        helper.assertFalse(menu.hasInventory(), "hasInventory");
        helper.assertValueEqual(menu.slots.size(), 0, "total slots");
        helper.succeed();
    }

    // --- 3. Slot rules ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void upgradeSlotAcceptsOnlyEnergyUpgrade(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        Player p = player(helper, owner, 0);
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
        var slot = menu.getUpgradeSlots().get(0);
        helper.assertTrue(slot.mayPlace(energy(1)), "Energy accepted");
        helper.assertFalse(slot.mayPlace(new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get())), "Sight rejected by mayPlace");
        helper.assertFalse(slot.mayPlace(new ItemStack(Items.DIRT)), "Dirt rejected by mayPlace");
        helper.assertTrue(!be.getUpgrades().insertItem(0, new ItemStack(Items.DIRT), true).isEmpty(), "Dirt rejected by handler");
        helper.assertTrue(!be.getUpgrades().insertItem(0, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get()), true).isEmpty(),
                "Sight rejected by handler");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void shiftClickMovesEnergyUpgradesUpToCapAndBack(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        Player p = player(helper, owner, 0);
        p.getInventory().setItem(0, energy(10));
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
        int invIndex = 1 + 27; // hotbar slot 0
        menu.quickMoveStack(p, invIndex);
        helper.assertValueEqual(be.getUpgrades().getStackInSlot(0).getCount(), 4, "in station after shift-click");
        helper.assertValueEqual(countEnergy(p), 6, "left in inventory");
        menu.quickMoveStack(p, 0);
        helper.assertTrue(be.getUpgrades().getStackInSlot(0).isEmpty(), "slot emptied");
        helper.assertValueEqual(countEnergy(p), 10, "back in inventory");
        helper.succeed();
    }

    // --- 4. Config cap (own batch) ---

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_station_max_energy_upgrades")
    public static void capFollowsConfig(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.CHARGING_STATION_MAX_ENERGY_UPGRADES);
        ServerConfig.CHARGING_STATION_MAX_ENERGY_UPGRADES.set(2);
        try {
            UUID owner = UUID.randomUUID();
            ChargingStationBlockEntity be = station(helper, owner);
            Player p = player(helper, owner, 0);
            p.getInventory().setItem(0, energy(10));
            ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
            menu.quickMoveStack(p, 1 + 27);
            helper.assertValueEqual(be.getUpgrades().getStackInSlot(0).getCount(), 2, "in station");
            helper.assertValueEqual(countEnergy(p), 8, "in inventory");
        } finally {
            ServerConfig.CHARGING_STATION_MAX_ENERGY_UPGRADES.set(original);
        }
        helper.succeed();
    }

    // --- 5. Automation ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void stationExposesNoUpgradeItemHandler(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        be.getUpgrades().setStackInSlot(0, energy(3));
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(REL);
        for (Direction dir : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null }) {
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, dir);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    helper.assertFalse(handler.getStackInSlot(i).is(ModItems.upgrade(UpgradeType.ENERGY).get()),
                            "Handler on " + dir + " exposes upgrades");
                }
            }
        }
        helper.succeed();
    }

    // --- 6. Persistence ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void upgradesSurviveSaveAndLoad(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        be.getUpgrades().setStackInSlot(0, energy(3));
        ServerLevel level = helper.getLevel();
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        ChargingStationBlockEntity copy = new ChargingStationBlockEntity(helper.absolutePos(REL), level.getBlockState(helper.absolutePos(REL)));
        copy.loadWithComponents(tag, level.registryAccess());
        helper.assertValueEqual(copy.getUpgrades().getStackInSlot(0).getCount(), 3, "loaded count");
        helper.assertTrue(copy.getUpgrades().getStackInSlot(0).is(ModItems.upgrade(UpgradeType.ENERGY).get()), "loaded item");
        helper.succeed();
    }

    // --- 7. Lost access ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void lostAccessBlocksQuickMoveClick(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        Player p = player(helper, owner, 0);
        be.getUpgrades().setStackInSlot(0, energy(3));
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
        be.setOwner(UUID.randomUUID());
        menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
        helper.assertValueEqual(be.getUpgrades().getStackInSlot(0).getCount(), 3, "station keeps upgrades");
        helper.assertValueEqual(countEnergy(p), 0, "player gained nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void withAccessQuickMoveClickWorks(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        ChargingStationBlockEntity be = station(helper, owner);
        Player p = player(helper, owner, 0);
        be.getUpgrades().setStackInSlot(0, energy(3));
        ChargingStationMenu menu = new ChargingStationMenu(1, p.getInventory(), be, true);
        menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
        helper.assertTrue(be.getUpgrades().getStackInSlot(0).isEmpty(), "slot emptied");
        helper.assertValueEqual(countEnergy(p), 3, "player received");
        helper.succeed();
    }
}
