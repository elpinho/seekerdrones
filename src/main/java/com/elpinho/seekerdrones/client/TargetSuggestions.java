package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.EntityType;

/**
 * Auto-complete for the Programming Station's target rows (DESIGN.md section 7.2): a dropdown under the focused box
 * with the entity types, entity tags or online players matching the typed text, by the row's kind. All sources are
 * client-side. Up/Down move the selection, Tab or a click accepts it, Enter accepts it after moving with the arrows,
 * and Escape hides the dropdown until the text changes.
 */
final class TargetSuggestions {
    private static final int VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT = 12;
    private static final int PADDING = 3;
    private static final int SCROLLBAR_WIDTH = 2;
    private static final int BACKGROUND_COLOR = 0xE0000000;
    private static final int SCROLLBAR_COLOR = 0xFF808080;
    private static final int TEXT_COLOR = 0xAAAAAA;
    private static final int SELECTED_COLOR = 0xFFFF00;
    private static final int DETAIL_COLOR = 0x707070;
    /** Above the slot items, below tooltips. */
    private static final int Z = 300;

    /** One suggestion: the text put into the box, and an optional detail shown after it (an entity's name). */
    private record Suggestion(String value, @Nullable Component detail) {}

    private final Font font;
    private final Consumer<String> onAccept;
    private List<Suggestion> suggestions = List.of();
    private int selected;
    private int scroll;
    /** Whether the selection was moved with the arrow keys, so Enter accepts it instead of committing the typed text. */
    private boolean navigated;
    private int x;
    private int y;
    private int width;

    TargetSuggestions(Font font, Consumer<String> onAccept) {
        this.font = font;
        this.onAccept = onAccept;
    }

    /**
     * Shows the suggestions for the text in {@code box}. Entries that {@code excluded} rejects (e.g. the list's other
     * rows) aren't suggested, and neither are blacklisted ones (section 3.3).
     */
    void show(EditBox box, TargetEntry.Kind kind, Predicate<TargetEntry> excluded, int screenWidth) {
        String input = box.getValue().trim();
        List<Suggestion> found = find(kind, input, entry -> excluded.test(entry) || TargetBlacklist.blocks(entry));
        // Nothing left to complete.
        if (found.size() == 1 && found.getFirst().value().equalsIgnoreCase(input)) {
            found = List.of();
        }
        suggestions = found;
        selected = 0;
        scroll = 0;
        navigated = false;
        int widest = 0;
        for (Suggestion suggestion : suggestions) {
            widest = Math.max(widest, font.width(label(suggestion)));
        }
        width = Math.max(box.getWidth(), widest + 2 * PADDING + SCROLLBAR_WIDTH);
        x = Math.max(2, Math.min(box.getX(), screenWidth - 2 - width));
        y = box.getY() + box.getHeight() + 1;
    }

    void hide() {
        suggestions = List.of();
    }

    boolean isVisible() {
        return !suggestions.isEmpty();
    }

    boolean isMouseOver(double mouseX, double mouseY) {
        return isVisible() && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + visibleRows() * ROW_HEIGHT;
    }

    // --- Input ---

    /** Returns whether the key was used. */
    boolean keyPressed(int keyCode) {
        if (!isVisible()) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> move(-1);
            case GLFW.GLFW_KEY_DOWN -> move(1);
            case GLFW.GLFW_KEY_TAB -> accept(selected);
            case GLFW.GLFW_KEY_ESCAPE -> hide();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (navigated) {
                    accept(selected);
                    return true;
                }
                // The box commits the typed text.
                hide();
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Returns whether the click was on the dropdown. */
    boolean mouseClicked(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        accept(row);
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, suggestions.size() - VISIBLE_ROWS));
        return true;
    }

    void mouseMoved(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            selected = row;
        }
    }

    private void move(int delta) {
        selected = Math.floorMod(selected + delta, suggestions.size());
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + VISIBLE_ROWS) {
            scroll = selected - VISIBLE_ROWS + 1;
        }
        navigated = true;
    }

    private void accept(int index) {
        String value = suggestions.get(index).value();
        onAccept.accept(value);
        // Setting the box's text shows the suggestions again; they stay hidden until the player types.
        hide();
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int row = scroll + (int) ((mouseY - y) / ROW_HEIGHT);
        return row < suggestions.size() ? row : -1;
    }

    private int visibleRows() {
        return Math.min(VISIBLE_ROWS, suggestions.size());
    }

    // --- Rendering ---

    void render(GuiGraphics graphics) {
        if (!isVisible()) {
            return;
        }
        int rows = visibleRows();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, Z);
        graphics.fill(x, y, x + width, y + rows * ROW_HEIGHT, BACKGROUND_COLOR);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            Suggestion suggestion = suggestions.get(index);
            int textY = y + i * ROW_HEIGHT + 2;
            int end = graphics.drawString(font, suggestion.value(), x + PADDING, textY, index == selected ? SELECTED_COLOR : TEXT_COLOR);
            if (suggestion.detail() != null) {
                graphics.drawString(font, detailText(suggestion.detail()), end, textY, DETAIL_COLOR);
            }
        }
        if (suggestions.size() > VISIBLE_ROWS) {
            int trackHeight = rows * ROW_HEIGHT;
            int thumbHeight = Math.max(4, trackHeight * VISIBLE_ROWS / suggestions.size());
            int thumbY = y + (trackHeight - thumbHeight) * scroll / (suggestions.size() - VISIBLE_ROWS);
            graphics.fill(x + width - SCROLLBAR_WIDTH, thumbY, x + width, thumbY + thumbHeight, SCROLLBAR_COLOR);
        }
        graphics.pose().popPose();
    }

    private String label(Suggestion suggestion) {
        return suggestion.detail() != null ? suggestion.value() + detailText(suggestion.detail()) : suggestion.value();
    }

    private static String detailText(Component detail) {
        return " (" + detail.getString() + ")";
    }

    // --- Matching ---

    private static List<Suggestion> find(TargetEntry.Kind kind, String input, Predicate<TargetEntry> excluded) {
        List<Ranked> found = new ArrayList<>();
        switch (kind) {
            case ENTITY_TYPE -> {
                String query = input.toLowerCase(Locale.ROOT);
                for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                    int rank = rank(query, id);
                    if (rank >= 0 && !excluded.test(new TargetEntry(kind, id.toString()))) {
                        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
                        found.add(new Ranked(new Suggestion(id.toString(), type.getDescription()), rank));
                    }
                }
            }
            case TAG -> {
                String query = (input.startsWith("#") ? input.substring(1) : input).toLowerCase(Locale.ROOT);
                BuiltInRegistries.ENTITY_TYPE.getTagNames().forEach(tag -> {
                    int rank = rank(query, tag.location());
                    if (rank >= 0 && !excluded.test(new TargetEntry(kind, tag.location().toString()))) {
                        found.add(new Ranked(new Suggestion("#" + tag.location(), null), rank));
                    }
                });
            }
            case PLAYER_NAME -> {
                // Online players from the tab list. Offline names can still be typed.
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection != null) {
                    String query = input.toLowerCase(Locale.ROOT);
                    for (PlayerInfo info : connection.getOnlinePlayers()) {
                        String name = info.getProfile().getName();
                        int rank = rankWords(query, name.toLowerCase(Locale.ROOT));
                        if (rank >= 0 && StringUtil.isValidPlayerName(name) && !excluded.test(new TargetEntry(kind, name))) {
                            found.add(new Ranked(new Suggestion(name, null), rank));
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingInt(Ranked::rank).thenComparing(ranked -> ranked.suggestion().value(), String.CASE_INSENSITIVE_ORDER));
        return found.stream().map(Ranked::suggestion).toList();
    }

    private record Ranked(Suggestion suggestion, int rank) {}

    /**
     * How well {@code query} matches an ID, or -1 if it doesn't. A query with a namespace matches the whole ID. Without
     * one it matches the path in any namespace ({@code zomb} finds {@code mymod:zombie_knight}), or the namespace.
     */
    private static int rank(String query, ResourceLocation id) {
        if (query.indexOf(':') >= 0) {
            return rankWords(query, id.toString());
        }
        int path = rankWords(query, id.getPath());
        if (path >= 0) {
            return path;
        }
        return rankWords(query, id.getNamespace()) >= 0 ? 2 : -1;
    }

    /**
     * 0 if {@code candidate} starts with {@code query}, 1 if one of its later words does (words split at {@code _},
     * {@code /}, {@code .} and {@code :}), else -1. Like vanilla command suggestions.
     */
    private static int rankWords(String query, String candidate) {
        if (candidate.startsWith(query)) {
            return 0;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if ((c == '_' || c == '/' || c == '.' || c == ':') && candidate.startsWith(query, i + 1)) {
                return 1;
            }
        }
        return -1;
    }
}
