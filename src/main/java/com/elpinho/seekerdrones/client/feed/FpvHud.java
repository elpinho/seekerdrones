package com.elpinho.seekerdrones.client.feed;

import java.util.Random;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.client.DroneStatusScreen;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.FpvStatusPayload;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The FPV feed's HUD (DESIGN.md section 2.12): viewfinder brackets and a reticle, the drone's label, ID and state top
 * left, a compass tape top center, the live dot and signal bars top right, telemetry bottom left, energy and health
 * bottom right with the energy spend rate, and a key hint. Static fades in near the end of the remote's range, and
 * covers everything while connecting or after the signal is lost.
 */
public final class FpvHud {
    public static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "fpv_hud");
    private static final String KEY = "hud.seekerdrones.fpv.";

    private static final int MARGIN = 10;
    private static final int BRACKET = 14;
    private static final int INSET = 16;
    private static final int BAR_WIDTH = 70;
    private static final int COMPASS_HALF_WIDTH = 60;
    private static final int SIGNAL_BARS = 5;
    /** The most static the picture gets before the signal is lost, as the share of noisy pixels. */
    private static final float MAX_RANGE_STATIC = 0.35F;
    private static final double SPEED_SMOOTHING = 0.2;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xC8DCE4EC;
    private static final int FAINT = 0x46FFFFFF;
    private static final int FRAME = 0xAAFFFFFF;
    private static final int RED = 0xFFF45B5B;
    private static final int NIGHT_TEXT = 0xFF8CFFA0;
    private static final int NIGHT_TINT = 0x3820FF50;
    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private static final int NOISE_WIDTH = 160;
    private static final int NOISE_HEIGHT = 90;
    private static final int NOISE_FRAME_MILLIS = 50;
    private static final ResourceLocation NOISE = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "fpv_static");

    @Nullable
    private static DynamicTexture noise;
    private static float noiseAmount = -1;
    private static long noiseMillis;
    private static final int[] scanlines = new int[6];
    private static final Random random = new Random();

    private static double speed;
    private static double lastX = Double.NaN;
    private static double lastZ;
    private static double lastY;
    private static long lastSpeedTick;

    private FpvHud() {}

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, FpvHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!FpvFeedClient.isActive()) {
            lastX = Double.NaN;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        Font font = minecraft.font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        DroneEntity drone = FpvFeedClient.drone();
        FpvFeedClient.Phase phase = FpvFeedClient.phase();
        boolean live = phase == FpvFeedClient.Phase.LIVE && drone != null;

        if (!live) {
            graphics.fill(0, 0, width, height, 0xFF14161A);
            drawNoise(graphics, width, height, 1.0F);
        } else {
            if (FpvFeedClient.nightVision()) {
                graphics.fill(0, 0, width, height, NIGHT_TINT);
            }
            float fraction = rangeFraction(minecraft, drone);
            float start = ServerConfig.get(ServerConfig.CAMERA_STATIC_START).floatValue();
            if (start > 0 && fraction > 1 - start) {
                drawNoise(graphics, width, height, Mth.clamp((fraction - (1 - start)) / start, 0, 1) * MAX_RANGE_STATIC);
            }
        }

        drawBrackets(graphics, width, height);
        if (live) {
            drawReticle(graphics, width / 2, height / 2);
            drawCompass(graphics, font, width / 2, MARGIN + 4);
        }
        if (drone != null) {
            drawIdentity(graphics, font, drone);
        }
        drawLive(graphics, font, width, live ? signalBars(minecraft, drone) : 0);
        if (drone != null) {
            drawTelemetry(graphics, font, minecraft, drone, height);
            drawBars(graphics, font, drone, width, height);
        }
        Component hint = Component.translatable(KEY + "hint", minecraft.options.keyShift.getTranslatedKeyMessage(),
                FpvFeedClient.NIGHT_VISION_KEY.getTranslatedKeyMessage());
        Kit.smallText(graphics, font, hint, (width - Kit.smallWidth(font, hint)) / 2F, height - MARGIN - 5, DIM, true);

        if (!live) {
            Component message = Component.translatable(KEY + (phase == FpvFeedClient.Phase.SIGNAL_LOST ? "signal_lost" : "connecting"));
            boolean blink = (Util.getMillis() / 500) % 2 == 0;
            if (phase == FpvFeedClient.Phase.SIGNAL_LOST || blink) {
                graphics.drawCenteredString(font, message, width / 2, height / 2 - 4,
                        phase == FpvFeedClient.Phase.SIGNAL_LOST ? RED : WHITE);
            }
        }
    }

    // --- Signal ---

    /** How far the drone is, as a fraction of the remote's range. 0 for a command feed, which has no range. */
    private static float rangeFraction(Minecraft minecraft, DroneEntity drone) {
        if (!FpvFeedClient.isRemote()) {
            return 0;
        }
        double range = Math.max(1, ServerConfig.get(ServerConfig.REMOTE_RANGE));
        return (float) (minecraft.player.distanceTo(drone) / range);
    }

    private static int signalBars(Minecraft minecraft, DroneEntity drone) {
        return Mth.clamp(Mth.ceil((1 - rangeFraction(minecraft, drone)) * SIGNAL_BARS), 1, SIGNAL_BARS);
    }

    /** Static over the whole screen: {@code amount} is the share of noisy pixels, 1 for a full screen of it. */
    private static void drawNoise(GuiGraphics graphics, int width, int height, float amount) {
        long now = Util.getMillis();
        if (noise == null) {
            noise = new DynamicTexture(NOISE_WIDTH, NOISE_HEIGHT, false);
            Minecraft.getInstance().getTextureManager().register(NOISE, noise);
        }
        if (now - noiseMillis >= NOISE_FRAME_MILLIS || Math.abs(amount - noiseAmount) > 0.05F) {
            noiseMillis = now;
            noiseAmount = amount;
            NativeImage image = noise.getPixels();
            if (image != null) {
                for (int y = 0; y < NOISE_HEIGHT; y++) {
                    for (int x = 0; x < NOISE_WIDTH; x++) {
                        int pixel = 0;
                        if (random.nextFloat() < amount) {
                            int gray = 80 + random.nextInt(176);
                            int alpha = amount >= 1 ? 255 : 90 + random.nextInt(110);
                            // NativeImage is ABGR.
                            pixel = alpha << 24 | gray << 16 | gray << 8 | gray;
                        }
                        image.setPixelRGBA(x, y, pixel);
                    }
                }
                noise.upload();
            }
            for (int i = 0; i < scanlines.length; i++) {
                scanlines[i] = random.nextFloat() < amount * 2 ? random.nextInt(Math.max(1, height)) : -1;
            }
        }
        graphics.blit(NOISE, 0, 0, width, height, 0, 0, NOISE_WIDTH, NOISE_HEIGHT, NOISE_WIDTH, NOISE_HEIGHT);
        for (int line : scanlines) {
            if (line >= 0) {
                graphics.fill(0, line, width, line + 1, 0x30FFFFFF);
            }
        }
    }

    // --- Frame ---

    private static void drawBrackets(GuiGraphics graphics, int width, int height) {
        int right = width - MARGIN;
        int bottom = height - MARGIN;
        graphics.fill(MARGIN, MARGIN, MARGIN + BRACKET, MARGIN + 1, FRAME);
        graphics.fill(MARGIN, MARGIN, MARGIN + 1, MARGIN + BRACKET, FRAME);
        graphics.fill(right - BRACKET, MARGIN, right, MARGIN + 1, FRAME);
        graphics.fill(right - 1, MARGIN, right, MARGIN + BRACKET, FRAME);
        graphics.fill(MARGIN, bottom - 1, MARGIN + BRACKET, bottom, FRAME);
        graphics.fill(MARGIN, bottom - BRACKET, MARGIN + 1, bottom, FRAME);
        graphics.fill(right - BRACKET, bottom - 1, right, bottom, FRAME);
        graphics.fill(right - 1, bottom - BRACKET, right, bottom, FRAME);
    }

    private static void drawReticle(GuiGraphics graphics, int x, int y) {
        int color = 0xC8FFFFFF;
        graphics.fill(x - 7, y, x - 3, y + 1, color);
        graphics.fill(x + 3, y, x + 7, y + 1, color);
        graphics.fill(x, y - 7, x + 1, y - 3, color);
        graphics.fill(x, y + 3, x + 1, y + 7, color);
    }

    /** A tape of headings, one pixel per degree, with the 8 directions labelled and the bearing under the pointer. */
    private static void drawCompass(GuiGraphics graphics, Font font, int centerX, int y) {
        float yaw = Mth.wrapDegrees(FpvFeedClient.yaw());
        graphics.fill(centerX - COMPASS_HALF_WIDTH, y, centerX + COMPASS_HALF_WIDTH, y + 1, 0x78FFFFFF);
        for (int degrees = 0; degrees < 360; degrees += 15) {
            float offset = Mth.wrapDegrees(degrees - yaw);
            if (Math.abs(offset) > COMPASS_HALF_WIDTH - 2) {
                continue;
            }
            int x = centerX + Math.round(offset);
            boolean major = degrees % 45 == 0;
            graphics.fill(x, y + 1, x + 1, y + (major ? 5 : 3), 0xB4FFFFFF);
            if (major) {
                String name = DIRECTIONS[degrees / 45];
                Kit.smallText(graphics, font, name, x - Kit.smallWidth(font, name) / 2F, y + 6, WHITE, true);
            }
        }
        graphics.fill(centerX, y - 4, centerX + 1, y - 1, RED);
        // Minecraft's yaw is 0 facing south; a bearing is 0 facing north.
        String bearing = String.format("%03d", Math.floorMod(Math.round(yaw) + 180, 360));
        Kit.smallText(graphics, font, bearing, centerX - Kit.smallWidth(font, bearing) / 2F, y + 14, DIM, true);
    }

    // --- Corners ---

    /** The label in the drone's color with the ID chip, and the state pill under them, as on the remote screen. */
    private static void drawIdentity(GuiGraphics graphics, Font font, DroneEntity drone) {
        String label = drone.getLabel();
        Component labelText = label.isEmpty() ? drone.getType().getDescription() : Component.literal(label);
        int x = INSET;
        int y = INSET;
        graphics.drawString(font, labelText, x, y + 1, FastColor.ARGB32.opaque(drone.getColor().getTextColor()), true);
        String id = "#" + FpvFeedClient.droneId();
        int chipX = x + font.width(labelText) + 4;
        int chipWidth = Kit.smallWidth(font, id) + 6;
        graphics.blitSprite(Kit.CHIP, chipX, y, chipWidth, 10);
        Kit.smallText(graphics, font, id, chipX + 3, y + 2.5F, Kit.CHIP_TEXT, false);
        Kit.pill(graphics, font, x, y + 13, DroneStatusScreen.stateLight(drone.getState()),
                Component.translatable(drone.getState().getTranslationKey()));
    }

    /** The blinking REC dot with "LIVE", and the signal bars under it. */
    private static void drawLive(GuiGraphics graphics, Font font, int width, int bars) {
        int right = width - INSET;
        Component live = Component.translatable(KEY + (FpvFeedClient.nightVision() ? "live_night" : "live"));
        int liveWidth = Kit.smallWidth(font, live);
        Kit.smallText(graphics, font, live, right - 6 - liveWidth, INSET + 1, FpvFeedClient.nightVision() ? NIGHT_TEXT : WHITE, true);
        if ((Util.getMillis() / 600) % 2 == 0) {
            graphics.fill(right - 4, INSET + 1, right, INSET + 5, RED);
        }
        int barColor = bars <= 1 ? RED : WHITE;
        int barsX = right - (SIGNAL_BARS * 3 - 1);
        for (int i = 0; i < SIGNAL_BARS; i++) {
            int barHeight = 2 + i * 2;
            int bottom = INSET + 20;
            graphics.fill(barsX + i * 3, bottom - barHeight, barsX + i * 3 + 2, bottom, i < bars ? barColor : FAINT);
        }
    }

    private static void drawTelemetry(GuiGraphics graphics, Font font, Minecraft minecraft, DroneEntity drone, int height) {
        updateSpeed(minecraft, drone);
        int y = height - INSET - 18;
        row(graphics, font, Component.translatable(KEY + "altitude"), Component.translatable(KEY + "altitude.value", Mth.floor(drone.getY())), y);
        row(graphics, font, Component.translatable(KEY + "distance"),
                Component.translatable(KEY + "distance.value", Math.round(minecraft.player.distanceTo(drone))), y + 8);
        row(graphics, font, Component.translatable(KEY + "speed"),
                Component.translatable(KEY + "speed.value", String.format("%.1f", speed)), y + 16);
    }

    private static void row(GuiGraphics graphics, Font font, Component label, Component value, int y) {
        Kit.smallText(graphics, font, label, INSET, y, DIM, true);
        Kit.smallText(graphics, font, value, INSET + 24, y, WHITE, true);
    }

    /** The drone's speed in blocks per second, from how far it moved each tick, smoothed. */
    private static void updateSpeed(Minecraft minecraft, DroneEntity drone) {
        long tick = minecraft.level.getGameTime();
        if (tick == lastSpeedTick) {
            return;
        }
        if (!Double.isNaN(lastX)) {
            double moved = Math.sqrt(Mth.lengthSquared(drone.getX() - lastX, drone.getY() - lastY, drone.getZ() - lastZ))
                    / Math.max(1, tick - lastSpeedTick) * 20;
            speed += (moved - speed) * SPEED_SMOOTHING;
        }
        lastSpeedTick = tick;
        lastX = drone.getX();
        lastY = drone.getY();
        lastZ = drone.getZ();
    }

    /** The energy and health bars, with the energy spend rate left of the energy icon. */
    private static void drawBars(GuiGraphics graphics, Font font, DroneEntity drone, int width, int height) {
        int x = width - INSET - BAR_WIDTH;
        int y = height - INSET - 18;
        FpvStatusPayload status = FpvFeedClient.status();
        float energy = status != null ? Kit.fraction(status.energy(), status.maxEnergy()) : 0;
        graphics.blitSprite(Kit.ICON_ENERGY, x - 10, y, 6, 8);
        bar(graphics, Kit.FILL_ENERGY_HORIZONTAL, x, y + 1, energy);
        if (status != null) {
            Component rate = Component.translatable("screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(Math.round(status.drainRate())));
            Kit.smallText(graphics, font, rate, x - 13 - Kit.smallWidth(font, rate), y + 1, WHITE, true);
        }
        graphics.blitSprite(Kit.ICON_HEALTH, x - 10, y + 11, 7, 6);
        bar(graphics, Kit.FILL_HEALTH_HORIZONTAL, x, y + 11, Kit.fraction(drone.getHealth(), drone.getMaxHealth()));
    }

    private static void bar(GuiGraphics graphics, ResourceLocation fill, int x, int y, float fraction) {
        graphics.blitSprite(Kit.GAUGE, x, y, BAR_WIDTH, 6);
        int filled = Math.round((BAR_WIDTH - 2) * fraction);
        if (filled > 0) {
            graphics.blitSprite(fill, x + 1, y + 1, filled, 4);
        }
        Kit.horizontalNotches(graphics, x, y, BAR_WIDTH, 6);
    }
}
