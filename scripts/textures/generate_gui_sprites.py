"""Generates the placeholder GUI sprites of the machine GUI kit (until the commissioned art replaces them).

Each sprite goes to textures/gui/sprites with a .mcmeta that sets its GUI scaling (nine-slice, stretch or tile), so
the artist can repaint a sprite at any size without touching the layout code. Colors follow the "Seeker Drones GUI
Redesign" mockup. Run from anywhere:

    python scripts/textures/generate_gui_sprites.py [--preview DIR]

Requires Pillow. The PNGs are committed, so the build doesn't depend on this script. --preview also writes 8x upscaled
copies to DIR.
"""

import argparse
import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SPRITES = ROOT / "src/main/resources/assets/seekerdrones/textures/gui/sprites"

CLEAR = (0, 0, 0, 0)
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)

# Vanilla panel colors.
PANEL = (198, 198, 198, 255)
PANEL_HIGHLIGHTED = (212, 212, 212, 255)
PANEL_LIGHT = WHITE
PANEL_SHADOW = (85, 85, 85, 255)
SLOT = (139, 139, 139, 255)
SLOT_SHADOW = (55, 55, 55, 255)

# The dark recessed display.
DISPLAY = (12, 18, 21, 255)
DISPLAY_SCANLINE = (19, 25, 28, 255)
CELL = (16, 27, 31, 255)
CELL_BORDER = (33, 52, 56, 255)
GAUGE = (26, 30, 36, 255)

ENERGY_HIGH = (116, 242, 148, 255)
ENERGY = (53, 194, 92, 255)
ENERGY_LOW = (27, 113, 52, 255)
HEALTH_HIGH = (255, 128, 134, 255)
HEALTH = (224, 68, 75, 255)
HEALTH_LOW = (139, 29, 35, 255)

LIGHTS_OK = (74, 222, 128, 255)
LIGHTS = {
    "ok": (74, 222, 128),
    "warn": (251, 191, 36),
    "bad": (244, 91, 91),
    "idle": (123, 132, 144),
}


def new(w, h, color=CLEAR):
    return Image.new("RGBA", (w, h), color)


def rect(img, x, y, w, h, color):
    for i in range(x, x + w):
        for j in range(y, y + h):
            img.putpixel((i, j), color)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


def gradient(stops, t):
    """Color at t (0..1) along a list of (position, color) stops."""
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return lerp(c0, c1, 0 if p1 == p0 else (t - p0) / (p1 - p0))
    return stops[-1][1]


def outline(img, color, rounded=False):
    w, h = img.size
    for x in range(w):
        img.putpixel((x, 0), color)
        img.putpixel((x, h - 1), color)
    for y in range(h):
        img.putpixel((0, y), color)
        img.putpixel((w - 1, y), color)
    if rounded:
        for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
            img.putpixel((x, y), CLEAR)


def inset(img, fill, dark=SLOT_SHADOW, light=WHITE):
    """A recessed box: dark top and left edge, light bottom and right edge."""
    w, h = img.size
    rect(img, 0, 0, w, h, fill)
    for x in range(w - 1):
        img.putpixel((x, 0), dark)
    for y in range(h - 1):
        img.putpixel((0, y), dark)
    for x in range(1, w):
        img.putpixel((x, h - 1), light)
    for y in range(1, h):
        img.putpixel((w - 1, y), light)
    return img


def bevel(w, h, fill, light, shadow, width, edge=BLACK, open_left=False):
    """A raised box with a black outline and rounded corners, lit from the top left."""
    img = new(w, h, fill)
    for x in range(w):
        for y in range(h):
            lit = x < 1 + width or y < 1 + width
            shaded = x >= w - 1 - width or y >= h - 1 - width
            if open_left:
                lit = y < 1 + width
            if lit and not shaded:
                img.putpixel((x, y), light)
            elif shaded and not lit:
                img.putpixel((x, y), shadow)
    outline(img, edge)
    # Rounded corners: two clear pixels and an outline pixel tucked inside.
    corners = [(w - 1, 0, -1, 1), (w - 1, h - 1, -1, -1)]
    if not open_left:
        corners += [(0, 0, 1, 1), (0, h - 1, 1, -1)]
    for x, y, dx, dy in corners:
        img.putpixel((x, y), CLEAR)
        img.putpixel((x + dx, y), CLEAR)
        img.putpixel((x, y + dy), CLEAR)
        img.putpixel((x + dx, y + dy), edge)
    if open_left:
        # The tab continues under the frame, so its left side has no outline.
        for y in range(1, h - 1):
            img.putpixel((0, y), light if y < 1 + width else (shadow if y >= h - 1 - width else fill))
    return img


def frame():
    return bevel(16, 16, PANEL, PANEL_LIGHT, PANEL_SHADOW, 2)


def side_tab(fill):
    return bevel(24, 24, fill, PANEL_LIGHT, PANEL_SHADOW, 2, open_left=True)


def display():
    img = new(16, 16)
    inset(img, DISPLAY)
    for y in range(2, 15, 2):
        for x in range(1, 15):
            img.putpixel((x, y), DISPLAY_SCANLINE)
    return img


def slot(size):
    img = inset(new(size, size), SLOT)
    img.putpixel((size - 1, 0), SLOT)
    img.putpixel((0, size - 1), SLOT)
    return img


def cell():
    img = new(16, 16, CELL)
    outline(img, CELL_BORDER)
    return img


def button(fill, light, shadow, edge):
    img = new(16, 16, fill)
    for x in range(1, 15):
        img.putpixel((x, 1), light)
        img.putpixel((x, 14), shadow)
    for y in range(1, 15):
        img.putpixel((1, y), light)
        img.putpixel((14, y), shadow)
    outline(img, edge)
    return img


def rounded_box(w, h, fill, edge):
    img = new(w, h, fill)
    outline(img, edge, rounded=True)
    return img


def light(name):
    r, g, b = LIGHTS[name]
    img = new(6, 6)
    if name != "idle":
        # A faint glow around the 4x4 lamp.
        for x in range(6):
            for y in range(6):
                corner = x in (0, 5) and y in (0, 5)
                if not corner:
                    img.putpixel((x, y), (r, g, b, 70))
    rect(img, 1, 1, 4, 4, (r, g, b, 255))
    img.putpixel((1, 1), tuple(min(255, c + 60) for c in (r, g, b)) + (255,))
    return img


def fill_vertical_gauge(stops):
    """A fill for vertical gauges: shaded across, so stretching it to any height keeps the look."""
    img = new(12, 4)
    for x in range(12):
        rect(img, x, 0, 1, 4, gradient(stops, x / 11))
    return img


def fill_horizontal_bar(stops):
    img = new(4, 6)
    for y in range(6):
        rect(img, 0, y, 4, 1, gradient(stops, y / 5))
    return img


def glass():
    img = new(16, 4)
    for x in range(16):
        t = x / 15
        alpha = round(36 * max(0.0, 1 - t / 0.45))
        rect(img, x, 0, 1, 4, (255, 255, 255, alpha))
    return img


def pixels(rows, colors):
    img = new(len(rows[0]), len(rows))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != ".":
                img.putpixel((x, y), colors[key])
    return img


ENERGY_ICON = pixels(["...YYY", "..YYY.", ".YYY..", "YYYYYY", "..YYY.", ".YYY..", "YYY...", "YY...."], {"Y": (255, 216, 74, 255)})
HEALTH_ICON = pixels([".RR.RR.", "RRRRRRR", "RRRRRRR", ".RRRRR.", "..RRR..", "...R..."], {"R": (224, 68, 75, 255)})
REDSTONE_ROWS = ["...R....", ".R..R.R.", "..RRR...", "R.RRRR.R", "..RRRR..", ".R.RR.R.", "...R..R."]


def toggle_knob():
    return button((154, 154, 154, 255), (208, 208, 208, 255), PANEL_SHADOW, BLACK)


def shaft():
    """The Deploying Station's launch shaft: a night sky over the pad, in a recessed frame."""
    w, h = 94, 66
    img = new(w, h)
    stops = [(0, (11, 24, 48, 255)), (0.7, (12, 20, 24, 255)), (1, DISPLAY)]
    for y in range(h):
        color = gradient(stops, y / (h - 1))
        if y % 2 == 0:
            color = tuple(min(255, c + 5) for c in color[:3]) + (255,)
        rect(img, 0, y, w, 1, color)
    for x, y in ((6, 6), (80, 10), (15, 22), (72, 30), (86, 4), (4, 40)):
        img.putpixel((x + 1, y + 1), (159, 182, 214, 160))
    for x in range(w - 1):
        img.putpixel((x, 0), SLOT_SHADOW)
    for y in range(h - 1):
        img.putpixel((0, y), SLOT_SHADOW)
    for x in range(1, w):
        img.putpixel((x, h - 1), WHITE)
    for y in range(1, h):
        img.putpixel((w - 1, y), WHITE)
    return img


def hazard():
    img = new(6, 6)
    for x in range(6):
        for y in range(6):
            img.putpixel((x, y), (233, 183, 42, 255) if (x + y) % 6 < 3 else (29, 29, 29, 255))
    return img


def housing():
    img = new(16, 16, (58, 61, 66, 255))
    rect(img, 1, 1, 14, 2, (42, 44, 48, 255))
    rect(img, 1, 13, 14, 2, (42, 44, 48, 255))
    rect(img, 1, 1, 2, 14, (42, 44, 48, 255))
    rect(img, 13, 1, 2, 14, (42, 44, 48, 255))
    outline(img, BLACK)
    return img


def pad():
    w, h = 48, 8
    img = new(w, h)
    for y in range(h):
        rect(img, 0, y, w, 1, gradient([(0, (89, 99, 110, 255)), (1, (43, 49, 56, 255))], y / (h - 1)))
    stripes = hazard()
    for x in range(w):
        for y in range(2):
            img.putpixel((x, y), stripes.getpixel((x % 6, y)))
    return img


def launch_button(pressed=False, disabled=False):
    """The big round launch button with a lip under it. Pressed, it sits on the lip."""
    size = 30
    img = new(size, size + 2)
    top = 1 if pressed else 0
    stops = [(0, (255, 138, 122, 255)), (0.45, (217, 38, 28, 255)), (1, (122, 15, 10, 255))]
    radius = size / 2

    def disc(oy, color_at):
        for x in range(size):
            for y in range(size):
                dx, dy = x + 0.5 - radius, y + 0.5 - radius
                d = math.hypot(dx, dy)
                if d <= radius:
                    color = BLACK if d > radius - 1 else color_at(x, y)
                    img.putpixel((x, y + oy), color)

    disc(2, lambda x, y: (74, 8, 5, 255))

    def face(x, y):
        hx, hy = size * 0.38, size * 0.32
        t = min(1.0, math.hypot(x + 0.5 - hx, y + 0.5 - hy) / (radius * 1.25))
        color = gradient(stops, t)
        if disabled:
            grey = sum(color[:3]) // 3
            color = tuple(round((c * 0.35 + grey * 0.65) * 0.6) for c in color[:3]) + (255,)
        return color

    disc(top + (1 if pressed else 0), face)
    return img


def cover():
    w, h = 38, 34
    img = new(w, h)
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            img.putpixel((x, y), lerp((190, 225, 255, 82), (120, 160, 200, 31), t))
    for x in range(w):
        img.putpixel((x, 0), (200, 230, 255, 140))
        img.putpixel((x, h - 1), (200, 230, 255, 140))
    for y in range(h):
        img.putpixel((0, y), (200, 230, 255, 140))
        img.putpixel((w - 1, y), (200, 230, 255, 140))
    # A hinge glint along the top.
    for x in range(2, w - 2):
        img.putpixel((x, 1), (230, 245, 255, 110))
    return img


def cover_open():
    """The cover flipped up: seen edge-on, it's a thin pane above the button."""
    w, h = 38, 5
    img = new(w, h)
    for y in range(h):
        for x in range(w):
            img.putpixel((x, y), lerp((200, 230, 255, 120), (140, 180, 220, 70), y / (h - 1)))
    for x in range(w):
        img.putpixel((x, 0), (220, 240, 255, 170))
        img.putpixel((x, h - 1), (200, 230, 255, 150))
    return img


def dock():
    w, h = 40, 5
    img = new(w, h)
    for y in range(h):
        rect(img, 0, y, w, 1, gradient([(0, (91, 100, 112, 255)), (1, (46, 52, 60, 255))], y / (h - 1)))
    outline(img, BLACK)
    return img


def radar():
    size = 74
    img = new(size, size)
    inset(img, DISPLAY)
    c = size / 2
    ring = (27, 58, 58, 255)
    for x in range(1, size - 1):
        for y in range(1, size - 1):
            d = math.hypot(x + 0.5 - c, y + 0.5 - c)
            if any(abs(d - r) < 0.5 for r in (34, 22, 11)):
                img.putpixel((x, y), ring)
            elif x == int(c) or y == int(c):
                if d < 34:
                    img.putpixel((x, y), ring)
            elif y % 2 == 0:
                img.putpixel((x, y), DISPLAY_SCANLINE)
    return img


def radar_sweep():
    """The radar's sweep, pointing up from the center. The game rotates it around the sprite's center."""
    size = 69
    img = new(size, size)
    c = size / 2
    ink = (143, 229, 214)
    for x in range(size):
        for y in range(size):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            d = math.hypot(dx, dy)
            if d > 34 or d < 0.5:
                continue
            # Angle clockwise from straight up, in degrees.
            angle = math.degrees(math.atan2(dx, -dy))
            behind = -angle  # the trail is counterclockwise of the line
            if 0 <= behind <= 45:
                alpha = round(120 * (1 - behind / 45))
                img.putpixel((x, y), ink + (alpha,))
            if abs(dx) < 0.6 and dy < 0:
                img.putpixel((x, y), ink + (220,))
    return img


def seg_distance(px, py, ax, ay, bx, by):
    """Distance from point p to the segment a-b."""
    dx, dy = bx - ax, by - ay
    t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


# The Drone Factory blueprint's geometry, in the display's inner pixels (the game draws the arm traces and rotor rings
# over it with the same numbers, see DroneFactoryScreen).
# The 3-pixel margin around the drone leaves room for the rotor rings (radius 12.5) inside the display.
BLUEPRINT_CENTER = (42, 36)
BLUEPRINT_ROTORS = ((14, 14), (70, 14), (14, 58), (70, 58))


def blueprint():
    """The Drone Factory's blueprint display: a grid, the drone's arms and body, in a recessed frame."""
    w, h = 86, 74
    img = new(w, h)
    inset(img, (10, 26, 43, 255))
    grid = (17, 42, 64, 255)
    arm = (18, 54, 86, 255)
    body = (14, 40, 64, 255)
    body_edge = (63, 134, 198, 255)
    cx, cy = BLUEPRINT_CENTER
    for x in range(1, w - 1):
        for y in range(1, h - 1):
            ix, iy = x - 1, y - 1
            px, py = ix + 0.5, iy + 0.5
            color = None
            # Lines every 5 pixels, placed so the grid is symmetric in both directions.
            if ix % 5 == 4 or iy % 5 == 3:
                color = grid
            if any(seg_distance(px, py, cx, cy, rx, ry) <= 3.5 for rx, ry in BLUEPRINT_ROTORS):
                color = arm
            # The body: a rounded box 24 x 54 at (30, 9).
            bx0, by0, bx1, by1, r = 30, 9, 54, 63, 5
            qx = min(max(px, bx0 + r), bx1 - r)
            qy = min(max(py, by0 + r), by1 - r)
            d = math.hypot(px - qx, py - qy)
            if d <= r:
                color = body_edge if d > r - 1 else body
            if color:
                img.putpixel((x, y), color)
    return img


ARROW_ROWS = [
    "..........#.......",
    "..........##......",
    "..........#.#.....",
    "..........#..#....",
    "###########...#...",
    "#..............#..",
    "#..............#..",
    "###########...#...",
    "..........#..#....",
    "..........#.#.....",
    "..........##......",
    "..........#.......",
]


def arrow(fill):
    """The Factory's progress arrow, filled from the left by the game. Its outline matches the slots' shadow."""
    img = new(18, 12)
    for y, row in enumerate(ARROW_ROWS):
        inside = False
        cells = [x for x, ch in enumerate(row) if ch == "#"]
        for x, ch in enumerate(row):
            if ch == "#":
                img.putpixel((x, y), SLOT_SHADOW)
            elif cells and cells[0] < x < cells[-1] and (y in (5, 6) or x > 10):
                img.putpixel((x, y), fill)
    return img


def remove_button(highlighted=False):
    """A small red button with a cross, for removing a row."""
    img = new(10, 10, (90, 34, 38, 255) if highlighted else (58, 26, 28, 255))
    outline(img, (106, 42, 46, 255))
    for i in range(3, 7):
        img.putpixel((i, i), (255, 154, 154, 255))
        img.putpixel((9 - i, i), (255, 154, 154, 255))
    return img


# Programming Station: the editor's tabs, the dark fields and buttons on a display, the slider knob and the icons.
FIELD = (5, 9, 11, 255)
FIELD_BORDER = (51, 80, 79, 255)
FIELD_EMPTY_BORDER = (41, 64, 63, 255)
DISPLAY_BUTTON = (21, 38, 42, 255)
DISPLAY_BUTTON_BORDER = (45, 74, 78, 255)
DISPLAY_BUTTON_HIGHLIGHTED = (27, 50, 54, 255)
DISPLAY_INK = (143, 229, 214, 255)


def editor_tab(fill, light):
    """A tab on top of the editor display: rounded top, open bottom (it joins the display)."""
    w, h = 16, 13
    img = new(w, h, fill)
    for x in range(w):
        img.putpixel((x, 0), BLACK)
    for y in range(h):
        img.putpixel((0, y), BLACK)
        img.putpixel((w - 1, y), BLACK)
    if light:
        for x in range(1, w - 1):
            img.putpixel((x, 1), light)
        for y in range(1, h):
            img.putpixel((1, y), light)
    for x, y in ((0, 0), (w - 1, 0)):
        img.putpixel((x, y), CLEAR)
    return img


def field(border, fill=FIELD):
    img = new(16, 16, fill)
    outline(img, border)
    return img


def dashed_field():
    """An empty target row: a field with a dashed border (tiled by its own nine-slice edges)."""
    img = new(16, 16, FIELD)
    for i in range(16):
        on = i % 4 < 2
        color = FIELD_EMPTY_BORDER if on else FIELD
        for x, y in ((i, 0), (i, 15), (0, i), (15, i)):
            img.putpixel((x, y), color)
    return img


def slider_knob():
    img = new(4, 10, (207, 214, 214, 255))
    outline(img, BLACK)
    return img


ICONS = {
    "info": (["..BBB..", ".BBWBB.", "BBBBBBB", "BBWWBBB", "BBBWBBB", "BBBWBBB", ".BWWWB.", "..BBB.."], {"B": (58, 123, 213, 255), "W": WHITE}),
    "pin": (["..RRR..", ".RRRRR.", ".RRWRR.", ".RRRRR.", "..RRR..", "...R...", "...R..."], {"R": (255, 106, 106, 255), "W": WHITE}),
    "egg": ([
        "..EEE..", ".EEEEE.", ".EdEEE.", "EEEEdEE", "EEdEEEE", "EEEEEdE", ".EEEEE.", "..EEE.."],
        {"E": (90, 166, 74, 255), "d": (47, 90, 40, 255)}),
    "tag": ([".H..H..", "HHHHHHH", ".H..H..", ".H..H..", "HHHHHHH", ".H..H.."], {"H": (201, 166, 255, 255)}),
    "target": ([
        "....R....", "..RRRRR..", ".R..R..R.", ".R.....R.", "RRR.R.RRR", ".R.....R.", ".R..R..R.", "..RRRRR..", "....R...."],
        {"R": (255, 112, 118, 255)}),
    "route": ([
        "..WWWWW..", ".W.....W.", "W.......W", "W...C...W", "W.......W", "W.......W", ".W.....W.", "..WW.WW.."],
        {"W": (159, 210, 255, 255), "C": WHITE}),
    "name_tag": (["..TTTTTTT", ".TTTTTTTT", "TT.TTTTTT", "TTTTTTTTT", ".TTTTTTTT", "..TTTTTTT"], {"T": (226, 196, 134, 255)}),
    # The generic machine Upgrades tab: two thin upward chevrons.
    "upgrades": ([
        "....L....", "...LGL...", "..LG.GL..", ".LG...GL.", "....L....", "...LGL...", "..LG.GL..", ".LG...GL."],
        {"L": (150, 240, 160, 255), "G": (60, 200, 110, 255)}),
    # Drone Remote: the command bar's icons and the Behavior tab's sliders.
    "recall": ([
        "...bb....", "..bbb....", ".bbbbbbb.", "..bbb..bb", "...bb...b", "........b", ".......bb", "..bbbbbb.", "..bbbbb.."],
        {"b": (126, 182, 255, 255)}),
    "hold": (["aa..aa"] * 7, {"a": (251, 191, 36, 255)}),
    "resume": (["g.....", "ggg...", "ggggg.", "gggggg", "ggggg.", "ggg...", "g....."], {"g": (74, 222, 128, 255)}),
    "settings": ([
        ".s...s...", ".s...s.S.", "SSS..s.s.", ".s..SSSs.", ".s...s.s.", ".s...sSSS", ".s...s.s.", ".s...s.s."],
        {"s": (154, 164, 176, 255), "S": (94, 104, 116, 255)}),
}


def nine_slice(w, h, border):
    return {"gui": {"scaling": {"type": "nine_slice", "width": w, "height": h, "border": border}}}


STRETCH = {"gui": {"scaling": {"type": "stretch"}}}


def tile(w, h):
    return {"gui": {"scaling": {"type": "tile", "width": w, "height": h}}}


def outputs():
    """Sprite path (under textures/gui/sprites) -> (image, mcmeta or None)."""
    sprites = {
        "frame": (frame(), nine_slice(16, 16, 4)),
        "display": (display(), nine_slice(16, 16, 1)),
        "slot": (slot(18), nine_slice(18, 18, 1)),
        "slot_large": (slot(26), nine_slice(26, 26, 1)),
        "cell": (cell(), nine_slice(16, 16, 1)),
        "gauge": (inset(new(16, 16), GAUGE), nine_slice(16, 16, 1)),
        "gauge_glass": (glass(), STRETCH),
        "fill/energy_vertical": (fill_vertical_gauge([(0, ENERGY_LOW), (0.3, ENERGY), (0.52, ENERGY_HIGH), (0.72, ENERGY), (1, ENERGY_LOW)]), STRETCH),
        "fill/energy_horizontal": (fill_horizontal_bar([(0, ENERGY_HIGH), (0.45, ENERGY), (1, ENERGY_LOW)]), STRETCH),
        "fill/health_horizontal": (fill_horizontal_bar([(0, HEALTH_HIGH), (0.45, HEALTH), (1, HEALTH_LOW)]), STRETCH),
        "side_tab": (side_tab(PANEL), nine_slice(24, 24, 4)),
        "side_tab_highlighted": (side_tab(PANEL_HIGHLIGHTED), nine_slice(24, 24, 4)),
        "button": (button((113, 113, 113, 255), (168, 168, 168, 255), (74, 74, 74, 255), BLACK), nine_slice(16, 16, 2)),
        "button_highlighted": (button((127, 139, 168, 255), (180, 190, 214, 255), (80, 88, 110, 255), WHITE), nine_slice(16, 16, 2)),
        "button_disabled": (button((60, 60, 60, 255), (80, 80, 80, 255), (44, 44, 44, 255), BLACK), nine_slice(16, 16, 2)),
        "pill": (rounded_box(16, 11, GAUGE, BLACK), nine_slice(16, 11, 2)),
        "chip": (rounded_box(8, 10, (42, 47, 54, 255), (42, 47, 54, 255)), nine_slice(8, 10, 2)),
        "toggle": (rounded_box(16, 12, GAUGE, BLACK), nine_slice(16, 12, 2)),
        "toggle_knob": (toggle_knob(), nine_slice(16, 16, 2)),
        "scrollbar": (new(3, 8, (20, 38, 42, 255)), STRETCH),
        "scrollbar_thumb": (new(3, 8, (143, 229, 214, 255)), STRETCH),
        "icon/energy": (ENERGY_ICON, None),
        "icon/health": (HEALTH_ICON, None),
        "icon/redstone": (pixels(REDSTONE_ROWS, {"R": (224, 40, 30, 255)}), None),
        "icon/redstone_off": (pixels(REDSTONE_ROWS, {"R": (90, 26, 22, 255)}), None),
        "deploying/shaft": (shaft(), STRETCH),
        "deploying/hazard": (hazard(), tile(6, 6)),
        "deploying/housing": (housing(), nine_slice(16, 16, 3)),
        "deploying/pad": (pad(), STRETCH),
        "deploying/launch_button": (launch_button(), None),
        "deploying/launch_button_pressed": (launch_button(pressed=True), None),
        "deploying/launch_button_disabled": (launch_button(disabled=True), None),
        "deploying/cover": (cover(), STRETCH),
        "deploying/cover_open": (cover_open(), STRETCH),
        "charging/dock": (dock(), STRETCH),
        "drone_status/radar": (radar(), STRETCH),
        "drone_status/radar_sweep": (radar_sweep(), None),
        "factory/blueprint": (blueprint(), STRETCH),
        "factory/arrow": (arrow(SLOT), None),
        "factory/arrow_filled": (arrow(LIGHTS_OK), None),
        "remove_button": (remove_button(), None),
        "remove_button_highlighted": (remove_button(highlighted=True), None),
        "field": (field(FIELD_BORDER), nine_slice(16, 16, 1)),
        "field_empty": (dashed_field(), nine_slice(16, 16, 1)),
        "display_button": (field(DISPLAY_BUTTON_BORDER, DISPLAY_BUTTON), nine_slice(16, 16, 1)),
        "display_button_highlighted": (field(DISPLAY_INK, DISPLAY_BUTTON_HIGHLIGHTED), nine_slice(16, 16, 1)),
        "slider_knob": (slider_knob(), nine_slice(4, 10, 1)),
        "programming/tab": (editor_tab((169, 169, 169, 255), (218, 218, 218, 255)), nine_slice(16, 13, 2)),
        "programming/tab_highlighted": (editor_tab((189, 189, 189, 255), (230, 230, 230, 255)), nine_slice(16, 13, 2)),
        "programming/tab_selected": (editor_tab(DISPLAY, None), nine_slice(16, 13, 2)),
    }
    for name, (rows, colors) in ICONS.items():
        sprites[f"icon/{name}"] = (pixels(rows, colors), None)
    for name in LIGHTS:
        sprites[f"light/{name}"] = (light(name), None)
    return sprites


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--preview", type=Path, help="also write 8x upscaled copies to this directory")
    args = parser.parse_args()

    for name, (img, meta) in outputs().items():
        path = SPRITES / f"{name}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        img.save(path)
        mcmeta = path.with_suffix(".png.mcmeta")
        if meta:
            mcmeta.write_text(json.dumps(meta, indent=2) + "\n")
        elif mcmeta.exists():
            mcmeta.unlink()
        print(f"wrote {path.relative_to(ROOT)}")
        if args.preview:
            preview = args.preview / f"{name}.png"
            preview.parent.mkdir(parents=True, exist_ok=True)
            img.resize((img.width * 8, img.height * 8), Image.NEAREST).save(preview)


if __name__ == "__main__":
    main()
