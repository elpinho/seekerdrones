package com.elpinho.seekerdrones.client.feed;

import java.util.Set;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.client.StationScreens;
import com.elpinho.seekerdrones.client.radar.StationScreen;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.network.FpvEndPayload;
import com.elpinho.seekerdrones.network.FpvStartPayload;
import com.elpinho.seekerdrones.network.FpvStatusPayload;
import com.elpinho.seekerdrones.network.StopFpvFeedPayload;
import com.elpinho.seekerdrones.feed.PilotInput;
import com.elpinho.seekerdrones.network.PilotInputPayload;
import com.elpinho.seekerdrones.network.RemoteCommandPayload;
import com.elpinho.seekerdrones.network.RequestRemoteStatusPayload;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client side of the FPV feed (DESIGN.md section 2.12): the camera sits on the watched drone and the mouse turns a
 * free-look gimbal, while the player's body stays frozen. The exit key leaves the feed, and a key toggles night vision.
 * <p>
 * While piloting (section 2.13) the movement keys fly the drone: their state is sent to the server when it changes,
 * Use opens the linked remote's screen over the feed, and the detonate key blows up an Explosive drone.
 */
public final class FpvFeedClient {
    public static final KeyMapping NIGHT_VISION_KEY = new KeyMapping("key.seekerdrones.fpv_night_vision", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N, "key.categories.seekerdrones");
    public static final KeyMapping EXIT_KEY = new KeyMapping("key.seekerdrones.fpv_exit", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT, "key.categories.seekerdrones");
    public static final KeyMapping DETONATE_KEY = new KeyMapping("key.seekerdrones.pilot_detonate", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X, "key.categories.seekerdrones");
    /** Below this change (degrees) the camera yaw isn't re-sent while flying. */
    private static final float YAW_RESEND_DEGREES = 1.0F;

    /** How long (ticks) to wait for the drone to reach the client before giving up. */
    private static final int CONNECT_TIMEOUT_TICKS = 200;
    /** The vanilla HUD hidden while watching. Chat, titles, subtitles, the action bar and the debug screen stay. */
    private static final Set<ResourceLocation> HIDDEN_LAYERS = Set.of(VanillaGuiLayers.CAMERA_OVERLAYS, VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR, VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.SPECTATOR_TOOLTIP, VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.EFFECTS, VanillaGuiLayers.BOSS_OVERLAY, VanillaGuiLayers.SLEEP_OVERLAY, VanillaGuiLayers.SCOREBOARD_SIDEBAR);

    /** What the screen shows. */
    public enum Phase {
        /** Waiting for the drone and its chunks to arrive: full static. */
        CONNECTING,
        LIVE,
        /** The drone was lost: full static with "Signal lost", then back to the player. */
        SIGNAL_LOST
    }

    private static boolean active;
    private static int entityId;
    private static String droneId = "";
    private static boolean remote;
    /** The Satellite Uplink a remote feed was opened through (section 7.9), or null. */
    @Nullable
    private static BlockPos uplink;
    /** The distance at which the signal is gone, 0 for a command feed. */
    private static int range;
    private static Phase phase = Phase.CONNECTING;
    private static int phaseTicks;
    private static float yaw;
    private static float pitch;
    private static boolean nightVision;
    /** The player flies the drone too (section 2.13). */
    private static boolean pilot;
    /** The input last sent to the server while piloting. */
    private static PilotInput sentInput = PilotInput.NONE;
    @Nullable
    private static CameraType savedCameraType;
    @Nullable
    private static FpvStatusPayload status;

    private FpvFeedClient() {}

    // --- State, for the HUD and the mixins ---

    public static boolean isActive() {
        return active;
    }

    public static Phase phase() {
        return phase;
    }

    public static String droneId() {
        return droneId;
    }

    /** Opened from a Drone Remote, so the signal drops with distance. */
    public static boolean isRemote() {
        return remote;
    }

    /** The Satellite Uplink the feed was opened through, or null: the signal is then measured from it. */
    @Nullable
    public static BlockPos uplink() {
        return uplink;
    }

    /** The distance at which the signal is gone: the remote's range or the Uplink's radius. */
    public static int range() {
        return range;
    }

    public static float yaw() {
        return yaw;
    }

    /** Whether the player flies the drone with this ID (section 2.13). */
    public static boolean isPiloting(String id) {
        return active && pilot && phase != Phase.SIGNAL_LOST && droneId.equals(id);
    }

    public static boolean isPiloting() {
        return active && pilot;
    }

    /** Boosting right now: piloting, moving and holding Sprint. */
    public static boolean isBoosting() {
        return isPiloting() && sentInput.boost() && sentInput.isMoving();
    }

    @Nullable
    public static FpvStatusPayload status() {
        return status;
    }

    public static boolean nightVision() {
        return active && nightVision && phase == Phase.LIVE;
    }

    /** The watched drone, once it has reached the client. */
    @Nullable
    public static DroneEntity drone() {
        Minecraft minecraft = Minecraft.getInstance();
        return active && minecraft.level != null && minecraft.level.getEntity(entityId) instanceof DroneEntity drone && !drone.isRemoved()
                ? drone : null;
    }

    /** Turns the gimbal by the mouse movement, like {@link net.minecraft.world.entity.Entity#turn}. False when not watching. */
    public static boolean turn(double yawDelta, double pitchDelta) {
        if (!active) {
            return false;
        }
        yaw += (float) yawDelta * 0.15F;
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
        return true;
    }

    // --- Payloads ---

    public static void start(FpvStartPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (!active) {
            savedCameraType = minecraft.options.getCameraType();
            yaw = minecraft.player.getYRot();
            pitch = minecraft.player.getXRot();
            nightVision = false;
        }
        active = true;
        entityId = payload.entityId();
        droneId = payload.droneId();
        remote = payload.remote();
        uplink = payload.uplink().orElse(null);
        range = payload.range();
        pilot = payload.pilot();
        sentInput = PilotInput.NONE;
        status = null;
        setPhase(Phase.CONNECTING);
        consume(EXIT_KEY, DETONATE_KEY);
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setScreen(null);
    }

    public static void end(FpvEndPayload payload) {
        if (!active) {
            return;
        }
        if (payload.signalLost() && ServerConfig.get(ServerConfig.CAMERA_SIGNAL_LOST_TICKS) > 0) {
            setPhase(Phase.SIGNAL_LOST);
        } else {
            reset();
        }
    }

    public static void updateStatus(FpvStatusPayload payload) {
        if (active) {
            status = payload;
        }
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    /**
     * Back to the player's own view. A feed opened through a Satellite Uplink goes back to the Uplink (section 7.9):
     * to the drone's remote screen when the player left it, or to the map when the signal was lost.
     */
    private static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean wasActive = active;
        BlockPos via = uplink;
        String id = droneId;
        boolean lost = phase == Phase.SIGNAL_LOST;
        uplink = null;
        if (active) {
            if (minecraft.player != null) {
                minecraft.setCameraEntity(minecraft.player);
            }
            if (savedCameraType != null) {
                minecraft.options.setCameraType(savedCameraType);
            }
        }
        active = false;
        pilot = false;
        sentInput = PilotInput.NONE;
        savedCameraType = null;
        status = null;
        nightVision = false;
        if (wasActive && via != null && minecraft.player != null && minecraft.screen == null && StationScreen.isOpen()) {
            if (lost) {
                StationScreens.reopen();
            } else {
                PacketDistributor.sendToServer(new RequestRemoteStatusPayload(id, true));
            }
        }
    }

    /** Leaves the feed now and tells the server. */
    private static void leave() {
        PacketDistributor.sendToServer(StopFpvFeedPayload.INSTANCE);
        reset();
    }

    // --- Events ---

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(NIGHT_VISION_KEY);
        event.register(EXIT_KEY);
        event.register(DETONATE_KEY);
    }

    public static void onClientTick(ClientTickEvent.Pre event) {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        phaseTicks++;
        if (phase == Phase.SIGNAL_LOST) {
            minecraft.setCameraEntity(minecraft.player);
            if (phaseTicks >= ServerConfig.get(ServerConfig.CAMERA_SIGNAL_LOST_TICKS)) {
                reset();
            }
            return;
        }
        DroneEntity drone = drone();
        if (drone != null) {
            if (phase != Phase.LIVE) {
                setPhase(Phase.LIVE);
            }
            if (minecraft.getCameraEntity() != drone) {
                minecraft.setCameraEntity(drone);
            }
        } else {
            // Its chunks are still on the way, or it just left the client's view: static until it's back.
            if (phase == Phase.LIVE) {
                setPhase(Phase.CONNECTING);
            }
            minecraft.setCameraEntity(minecraft.player);
            if (phaseTicks > CONNECT_TIMEOUT_TICKS) {
                leave();
                return;
            }
        }
        Options options = minecraft.options;
        boolean exit = false;
        while (EXIT_KEY.consumeClick()) {
            exit = true;
        }
        if (exit && minecraft.screen == null) {
            leave();
            return;
        }
        while (NIGHT_VISION_KEY.consumeClick()) {
            nightVision = !nightVision;
        }
        if (pilot) {
            tickPilot(minecraft, options);
        } else {
            consume(DETONATE_KEY);
        }
        // The body is frozen: these keys do nothing while watching.
        consume(options.keyDrop, options.keySwapOffhand, options.keyInventory, options.keyTogglePerspective, options.keyPickItem,
                options.keyUse, options.keyAttack);
        consume(options.keyHotbarSlots);
        if (options.getCameraType() != CameraType.FIRST_PERSON) {
            options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    /**
     * Piloting (section 2.13): sends the movement keys when they change (the drone brakes while a screen is open), opens
     * the linked remote's screen on Use, and detonates on the detonate key.
     */
    private static void tickPilot(Minecraft minecraft, Options options) {
        boolean open = false;
        while (options.keyUse.consumeClick()) {
            open = true;
        }
        if (open && minecraft.screen == null) {
            PacketDistributor.sendToServer(new RequestRemoteStatusPayload(droneId, true));
        }
        boolean detonate = false;
        while (DETONATE_KEY.consumeClick()) {
            detonate = true;
        }
        if (detonate && minecraft.screen == null && phase == Phase.LIVE) {
            PacketDistributor.sendToServer(new RemoteCommandPayload(droneId, RemoteCommand.DETONATE));
        }
        PilotInput input = PilotInput.NONE;
        if (minecraft.screen == null && phase == Phase.LIVE) {
            input = new PilotInput(axis(options.keyUp, options.keyDown), axis(options.keyLeft, options.keyRight),
                    axis(options.keyJump, options.keyShift), options.keySprint.isDown(), Mth.wrapDegrees(yaw));
        }
        boolean keysChanged = input.forward() != sentInput.forward() || input.left() != sentInput.left()
                || input.vertical() != sentInput.vertical() || input.boost() != sentInput.boost();
        boolean turned = input.isMoving() && Math.abs(Mth.wrapDegrees(input.yaw() - sentInput.yaw())) >= YAW_RESEND_DEGREES;
        if (keysChanged || turned) {
            sentInput = input;
            PacketDistributor.sendToServer(new PilotInputPayload(input));
        }
    }

    private static byte axis(KeyMapping positive, KeyMapping negative) {
        return (byte) ((positive.isDown() ? 1 : 0) - (negative.isDown() ? 1 : 0));
    }

    private static void consume(KeyMapping... keys) {
        for (KeyMapping key : keys) {
            while (key.consumeClick()) {
                // Dropped.
            }
        }
    }

    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (active) {
            var input = event.getInput();
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = input.down = input.left = input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
    }

    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (active && Minecraft.getInstance().screen == null) {
            event.setCanceled(true);
        }
    }

    public static void onRenderHand(RenderHandEvent event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (active && HIDDEN_LAYERS.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (active && phase == Phase.LIVE && event.getCamera().getEntity() instanceof DroneEntity) {
            event.setYaw(yaw);
            event.setPitch(pitch);
            event.setRoll(0);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }
}
