"""Generates the mod's placeholder textures (until the commissioned art replaces them).

Everything is drawn from scratch with a shared palette; no vanilla textures are copied. Run from anywhere:

    python scripts/textures/generate_textures.py [--preview DIR]

Requires Pillow. The PNGs are committed, so the build doesn't depend on this script. --preview also writes 16x
upscaled copies to DIR for checking the result, plus sheet.png: every item on an inventory slot and on the
hotbar (icons must read on both), and the block faces.
"""

import argparse
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / "src/main/resources/assets/seekerdrones/textures"

# Shared palette (RGBA). Keys are single characters so pixel grids can be written as text.
PALETTE = {
    ".": (0, 0, 0, 0),
    "k": (27, 29, 34, 255),  # outline
    "h": (42, 45, 51, 255),  # motor hub, darkest metal
    "d": (58, 63, 71, 255),  # dark metal
    "m": (90, 97, 107, 255),  # mid metal
    "l": (138, 146, 156, 255),  # light metal
    "L": (176, 183, 192, 255),  # metal highlight
    "r": (200, 204, 210, 255),  # rotor blade
    "s": (154, 160, 168, 255),  # rotor blade shadow
    "W": (214, 219, 226, 255),  # brightest metal (bolts)
    # The mod's accent: the seeker lens's pale blue, also used for machine lights and screens.
    "e": (168, 212, 240, 255),  # seeker lens
    "E": (228, 246, 255, 255),  # seeker lens glint
    "n": (22, 36, 50, 255),  # screen background
    "b": (70, 120, 160, 255),  # dim screen text
    # Tinted parts are drawn in greys, which the drone's color multiplies.
    "w": (255, 255, 255, 255),
    "c": (226, 226, 226, 255),
    "C": (180, 180, 180, 255),
}


def grid(rows, palette=None):
    """Turns a list of equal-length strings of palette keys into an image. `palette` adds or overrides keys."""
    colors = PALETTE | (palette or {})
    img = Image.new("RGBA", (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        assert len(row) == len(rows[0]), f"row {y} has length {len(row)}"
        for x, key in enumerate(row):
            img.putpixel((x, y), colors[key])
    return img


def fill(img, x, y, w, h, key):
    for i in range(x, x + w):
        for j in range(y, y + h):
            img.putpixel((i, j), PALETTE[key])


def paste_grid(img, x, y, rows):
    tile = grid(rows)
    img.alpha_composite(tile, (x, y))


def box_faces(u, v, w, h, d):
    """The UV rectangles (x, y, width, height) of a vanilla model box at texOffs(u, v) with size (w, h, d)."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),  # -X side
        "front": (u + d, v + d, w, h),  # -Z, the direction the drone faces
        "left": (u + d + w, v + d, d, h),  # +X side
        "back": (u + 2 * d + w, v + d, w, h),
    }


# --- Drone entity (UV layout must match DroneModel.createBodyLayer) ---------------------------------------------


def drone_entity():
    img = Image.new("RGBA", (64, 32))

    # Body 6x2x6 at (0, 0): a dark plate with light edges and corner bolts on top.
    body = box_faces(0, 0, 6, 2, 6)
    paste_grid(img, *body["top"][:2], [
        "lmmmml",
        "mddddm",
        "mdhhdm",
        "mdhhdm",
        "mddddm",
        "lmmmml",
    ])
    fill(img, *body["bottom"], "d")
    for side in ("right", "front", "left", "back"):
        x, y, w, _ = body[side]
        fill(img, x, y, w, 1, "l")
        fill(img, x, y + 1, w, 1, "m")

    # Lens 2x1x1 at (40, 0): the seeker eye on the front.
    lens = box_faces(40, 0, 2, 1, 1)
    for face in lens.values():
        fill(img, *face, "d")
    paste_grid(img, *lens["front"][:2], ["eE"])

    # Canopy (tinted shell) 4x1x4 at (24, 0).
    shell = box_faces(24, 0, 4, 1, 4)
    paste_grid(img, *shell["top"][:2], [
        "wwcc",
        "wccc",
        "cccC",
        "ccCC",
    ])
    fill(img, *shell["bottom"], "C")
    for side in ("right", "front", "left", "back"):
        fill(img, *shell[side], "C")

    # Arms 17x1x1 at (0, 8).
    arm = box_faces(0, 8, 17, 1, 1)
    fill(img, *arm["top"], "m")
    fill(img, *arm["bottom"], "d")
    for side in ("right", "front", "left", "back"):
        fill(img, *arm[side], "d")

    # Motors 2x2x2 at (36, 8).
    motor = box_faces(36, 8, 2, 2, 2)
    paste_grid(img, *motor["top"][:2], ["lm", "mh"])
    fill(img, *motor["bottom"], "h")
    for side in ("right", "front", "left", "back"):
        x, y, w, _ = motor[side]
        fill(img, x, y, w, 1, "m")
        fill(img, x, y + 1, w, 1, "h")

    # Rotors 6x0x6 at (0, 12): two-bladed props around a hub. Only the top face is painted: the model doesn't cull,
    # so it's also seen from below, and a painted bottom face would z-fight with it.
    rotor = box_faces(0, 12, 6, 0, 6)
    paste_grid(img, *rotor["top"][:2], [
        "rs....",
        "srr...",
        ".rhh..",
        "..hhr.",
        "...rrs",
        "....sr",
    ])

    return img


# --- Drone item (layer0 + tinted layer1) -----------------------------------------------------------------------

# Top-down quadcopter, drawn as one quadrant and mirrored: a rotor ring around each motor and blade, arms to the
# body, and the lens at the front (bottom edge). The icon is 14x14, centered in the 16x16 texture. Every part pairs dark pixels with light ones, so the icon keeps its
# shape on both the grey inventory slots and the near-black hotbar.
DRONE_ITEM_QUADRANT = [
    ".ddd...",
    "dLlrd..",
    "dlhld..",
    "drlmd..",
    ".dddmdd",
    "....dmm",
    "....dmm",
]


def mirrored(quadrant):
    top = [row + row[::-1] for row in quadrant]
    return top + top[::-1]


def drone_item():
    rows = mirrored(DRONE_ITEM_QUADRANT)
    rows[9] = rows[9][:6] + "eE" + rows[9][8:]
    img = Image.new("RGBA", (16, 16))
    img.alpha_composite(grid(rows), (1, 1))
    return img


# The canopy, tinted with the drone's color.
DRONE_ITEM_TINT = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "......wwcc......",
    "......wccc......",
    "......cccC......",
    "......ccCC......",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]


# --- Upgrades ----------------------------------------------------------------------------------------------------

# Every upgrade is the same metal module card with contact pins, and an 8x8 symbol on a colored face. In the
# symbols, "." is the face color (1), 2 and 3 are the upgrade's light and accent colors, and any palette key works.
def upgrade_card():
    """A 14x14 card with a light top-left bevel, a 10x10 face (key 1) at (3, 3), and gold pins along the bottom."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y == 15:
                key = "y" if x in (3, 5, 7, 8, 10, 12) else "."
            elif x in (0, 15) or y == 0:
                key = "."
            elif x in (1, 14) or y in (1, 14):
                key = "d"
            elif 3 <= x <= 12 and 3 <= y <= 12:
                key = "1"
            elif x == 13 or y == 13:
                key = "m"
            else:
                key = "L"
            row.append(key)
        rows.append(row)
    return rows


UPGRADES = {
    "energy": {
        "colors": {"1": (112, 84, 20), "2": (255, 222, 80), "3": (214, 160, 30)},
        "symbol": [
            "....222.",
            "...222..",
            "..222...",
            ".222222.",
            "...223..",
            "..223...",
            ".223....",
            ".2......",
        ],
    },
    "health": {
        "colors": {"1": (104, 28, 48), "2": (240, 92, 112), "3": (255, 200, 210)},
        "symbol": [
            "........",
            ".22..22.",
            "2322222 ",
            "22222222",
            ".222222.",
            "..2222..",
            "...22...",
            "........",
        ],
    },
    "sight": {
        "colors": {"1": (24, 78, 108), "2": (120, 200, 240), "3": (16, 30, 44)},
        "symbol": [
            "........",
            "..2222..",
            ".2WWWW2.",
            "2WW33WW2",
            "2WW33WW2",
            ".2WWWW2.",
            "..2222..",
            "........",
        ],
    },
    "patrol": {
        "colors": {"1": (28, 88, 58), "2": (130, 224, 156), "3": (230, 255, 236)},
        "symbol": [
            "..222.3.",
            ".2....33",
            "2....333",
            "2.......",
            "2......2",
            "2......2",
            ".2....2.",
            "..2222..",
        ],
    },
    "siren": {
        "colors": {"1": (100, 36, 28), "2": (255, 92, 70), "3": (255, 214, 96)},
        "symbol": [
            "3..33..3",
            ".3....3.",
            "...22...",
            "..W222..",
            "..W222..",
            ".222222.",
            "llllllll",
            "mmmmmmmm",
        ],
    },
    "explosive": {
        "colors": {"1": (120, 60, 20), "2": (36, 36, 40), "3": (255, 204, 80)},
        "symbol": [
            "......3.",
            ".....d.3",
            "..2222..",
            ".2m2222.",
            ".2m2222.",
            ".222222.",
            ".222222.",
            "..2222..",
        ],
    },
    "transmitter": {
        "colors": {"1": (68, 38, 108), "2": (204, 164, 255), "3": (255, 236, 255)},
        "symbol": [
            ".2.33.2.",
            "2..33..2",
            "2..22..2",
            ".2.22.2.",
            "...22...",
            "..2222..",
            ".22..22.",
            "22....22",
        ],
    },
    "player_seek": {
        # A blocky player in the default skin's colors: hair, skin, shirt and pants.
        "colors": {
            "1": (44, 52, 70),
            "2": (232, 182, 140),
            "3": (60, 190, 200),
            "4": (70, 80, 190),
            "5": (100, 66, 40),
        },
        "symbol": [
            "..5555..",
            "..2222..",
            "..2222..",
            "33333333",
            "22333322",
            "22333322",
            "..4444..",
            "..4444..",
        ],
    },
}


def upgrade(name):
    spec = UPGRADES[name]
    colors = {k: v + (255,) for k, v in spec["colors"].items()} | {"y": (222, 178, 60, 255)}
    rows = upgrade_card()
    for y, row in enumerate(spec["symbol"]):
        for x, key in enumerate(row.replace(" ", ".")):
            if key != ".":
                rows[4 + y][4 + x] = key
    return grid(["".join(row) for row in rows], colors)


# --- Components --------------------------------------------------------------------------------------------------


def drone_rotor():
    """A two-bladed prop across the diagonal, around a hub."""
    rows = [["."] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            s = x + y
            in_blade = 1 <= x <= 14 and 1 <= y <= 14 and not (5 <= x <= 10 and 5 <= y <= 10)
            if in_blade and s in (14, 15, 16):
                rows[y][x] = {14: "L", 15: "r", 16: "s"}[s]
            elif in_blade and s in (13, 17) and 1 <= x <= 14:
                rows[y][x] = "d"
    hub = [
        "..dddd..",
        ".dlLLld.",
        "dlLllmmd",
        "dLlhhlmd",
        "dLlhhlmd",
        "dlllmmmd",
        ".dmmmmd.",
        "..dddd..",
    ]
    for y, row in enumerate(hub):
        for x, key in enumerate(row):
            if key != ".":
                rows[4 + y][4 + x] = key
    # Close the blade tips.
    for x, y in ((13, 1), (14, 1), (14, 2), (1, 13), (1, 14), (2, 14)):
        rows[y][x] = "d"
    return grid(["".join(row) for row in rows])


# The Seeker Core: the drone's pale-blue lens in a round metal housing, with redstone contacts.
SEEKER_CORE = [
    "................",
    ".....dddddd.....",
    "...ddLLLLlldd...",
    "..dLLlllllllmd..",
    "..dLl.nnnn.lmd..",
    ".dLl.neeeen.lmd.",
    ".dLlneEEeeenlmd.",
    ".dLlneEeeeenlmd.",
    ".dLlneeennenlmd.",
    ".dLlneeennenlmd.",
    ".dlm.neeeen.mmd.",
    "..dlm.nnnn.mmd..",
    "..dmmmmmmmmmmd..",
    "...ddmmmmmmdd...",
    ".....dddddd.....",
    "................",
]


def seeker_core():
    rows = SEEKER_CORE
    # Fill the housing between the outline and the lens with metal.
    out = []
    for row in rows:
        chars = list(row)
        inside = [i for i, c in enumerate(chars) if c != "."]
        if inside:
            for i in range(inside[0], inside[-1] + 1):
                if chars[i] == ".":
                    chars[i] = "m"
        out.append("".join(chars))
    return grid(out)


# --- Machine blocks ----------------------------------------------------------------------------------------------

# Each machine is drawn as a physical device in dark gunmetal: a beveled frame with corner bolts around a recessed
# body that holds the machine's parts. The machine GUI kit's accents carry over (dark glass, teal glow, green energy,
# status lights, and each machine's own GUI motifs), not its gray panel. Lit faces are variants named after the block
# state that lights them (e.g. "_working"). Digits are the casing shades, dark to light.
MACHINE_PALETTE = {
    "0": (14, 17, 21, 255),  # gaps, outlines
    "1": (30, 34, 41, 255),
    "2": (40, 45, 53, 255),  # body
    "3": (49, 55, 64, 255),
    "4": (59, 66, 76, 255),
    "5": (68, 76, 86, 255),
    "6": (80, 89, 100, 255),  # frame
    "7": (98, 108, 120, 255),  # frame highlight
    "8": (128, 138, 150, 255),  # bolts
    "x": (13, 20, 24, 255),  # glass
    "y": (22, 34, 40, 255),  # glass reflection
    "a": (96, 52, 30, 255),  # copper shadow
    "b": (150, 82, 46, 255),  # copper
    "c": (199, 115, 63, 255),  # copper highlight
    "d": (240, 168, 112, 255),  # copper glint
    "g": (27, 113, 52, 255),  # energy shadow
    "G": (53, 194, 92, 255),  # energy
    "H": (150, 250, 175, 255),  # energy glint
    "T": (63, 120, 114, 255),  # dim teal
    "t": (143, 229, 214, 255),  # teal
    "u": (23, 101, 46, 255),  # energy shadow, darker
    "v": (32, 124, 58, 255),  # energy shadow, lighter
    "e": (22, 56, 36, 255),  # unlit light strip
    "i": (70, 78, 88, 255),  # idle light
    "j": (40, 46, 54, 255),  # idle light shadow
    "K": (214, 255, 246, 255),  # teal glint
    "z": (30, 62, 66, 255),  # faint teal
    "f": (42, 88, 90, 255),  # faint teal, lighter
    "Y": (204, 156, 40, 255),  # hazard yellow
    "Z": (156, 114, 30, 255),  # hazard yellow shadow
    "R": (196, 38, 32, 255),  # button red
    "S": (240, 112, 92, 255),  # button glint
    "q": (110, 22, 20, 255),  # button shadow
    "p": (84, 26, 22, 255),  # unlit redstone
    "P": (250, 56, 40, 255),  # lit redstone
    "n": (12, 28, 44, 255),  # blueprint screen
    "N": (22, 50, 74, 255),  # blueprint grid
    "B": (46, 104, 150, 255),  # blueprint line
    "o": (84, 150, 204, 255),  # blueprint line, lit
    "D": (64, 124, 214, 255),  # Direct mode blue
    "A": (226, 150, 40, 255),  # Template mode amber
    "w": (96, 60, 150, 255),  # card purple
    "m": (150, 110, 210, 255),  # card purple, lit
}


def framed(inner):
    """Wraps 12 rows of 12 keys in the frame: lit on the top and left, shaded on the bottom and right, with bolts."""
    assert len(inner) == 12 and all(len(row) == 12 for row in inner), inner
    rows = ["7777777777777774", "7866666666666683"]
    rows += ["76" + row + "31" for row in inner]
    rows += ["7833333333333381", "5111111111111111"]
    return rows


def worn(rows):
    """Speckles flat metal (the body and the frame) with nearby shades from a fixed hash, so it isn't flat."""
    out = []
    for y, row in enumerate(rows):
        keys = []
        for x, key in enumerate(row):
            h = (x * 73 + y * 151 + x * y * 17) % 23
            if key == "2" and h in (0, 7):
                key = "3"
            elif key == "2" and h == 13:
                key = "1"
            elif key == "6" and h in (3, 11):
                key = "5"
            keys.append(key)
        out.append("".join(keys))
    return out


def machine(rows):
    return grid(worn(rows), MACHINE_PALETTE)


def lit(rows, region, swaps):
    """The working variant: `swaps` applied inside region (x0, y0, x1, y1, inclusive)."""
    x0, y0, x1, y1 = region
    return ["".join(swaps.get(key, key) if x0 <= x <= x1 and y0 <= y <= y1 else key for x, key in enumerate(row))
            for y, row in enumerate(rows)]


# The bottom of every machine: vent slats.
MACHINE_BOTTOM = framed([
    "111111111111",
    "122222222222",
    "120000000032",
    "123444444432",
    "122222222222",
    "120000000032",
    "123444444432",
    "122222222222",
    "120000000032",
    "123444444432",
    "122222222222",
    "122222222222",
])

# Charging Station front: a cell clamped in a glass chamber between vents. While idle, a faint teal core glows in the
# chamber. While charging, the chamber fills with a green glow (animated): a bright glob drifts around inside it, sparks
# kindle at random, and the glow's shades shift.
CHARGING_STATION_FRONT = framed([
    "111111111111",
    "122577775222",
    "1330zzzz0332",
    "1000zffz0002",
    "1330ftTf0332",
    "1000fTTf0002",
    "1330fTTf0332",
    "1000zffz0002",
    "1330zzzz0332",
    "122577775222",
    "122222222222",
    "134432222ij2",
])
CHARGING_STATION_CHAMBER = (6, 4, 4, 7)  # x, y, width, height


def charging_station_front_working(frames=16):
    base = lit(lit(CHARGING_STATION_FRONT, (2, 2, 13, 13), {"i": "H", "j": "G"}),
               (6, 4, 9, 10), {"x": "g", "y": "g", "T": "g", "t": "g"})
    x0, y0, w, h = CHARGING_STATION_CHAMBER
    out = []
    for f in range(frames):
        rows = [list(row) for row in base]
        phase = 2 * math.pi * f / frames
        # The glob's path and size use whole-number frequencies, so the animation loops.
        gx = 1.5 + 0.9 * math.sin(phase + 0.4) + 0.3 * math.sin(3 * phase)
        gy = 3.0 + 1.6 * math.sin(2 * phase + 1.1) + 0.6 * math.cos(phase)
        r = 1.9 + 0.35 * math.sin(3 * phase + 0.5)
        sparks = random.Random(f * 7919)
        for cy in range(h):
            for cx in range(w):
                v = 1 - math.hypot(cx - gx, (cy - gy) * 0.85) / r
                key = "H" if v > 0.35 else "G" if v > -0.1 else "g"
                if key == "g":
                    shade = random.Random((f // 4) * 131 + cy * 17 + cx).random()
                    key = "u" if shade < 0.3 else "v" if shade > 0.7 else "g"
                if sparks.random() < 0.06:
                    key = "G" if key == "H" else "H" if key == "G" else "G"
                rows[y0 + cy][x0 + cx] = key
        out.append(machine(["".join(row) for row in rows]))
    return out


# Charging Station back: three upright capacitors with copper bands; their contacts glow while charging.
CHARGING_STATION_BACK = framed([
    "111111111111",
    "10T000T000T0",
    "157505750575",
    "167505670567",
    "167505670567",
    "1dcb0dcb0dcb",
    "1aba0aba0aba",
    "167505670567",
    "157505750575",
    "100000000000",
    "122222222222",
    "134432222ij2",
])
CHARGING_STATION_BACK_WORKING = lit(CHARGING_STATION_BACK, (2, 2, 13, 13), {"T": "H", "i": "H", "j": "G"})

# Charging Station sides: vents with a light strip across the middle, lit while charging.
CHARGING_STATION_SIDE = framed([
    "111111111111",
    "100000000002",
    "134444444432",
    "100000000002",
    "134444444432",
    "100000000002",
    "10eeeeeeee02",
    "100000000002",
    "134444444432",
    "100000000002",
    "134444444432",
    "122222222222",
])
CHARGING_STATION_SIDE_WORKING = lit(lit(CHARGING_STATION_SIDE, (2, 2, 13, 13), {"e": "G"}), (5, 8, 10, 8), {"G": "H"})

# Charging Station top: the landing pad, an emitter ring around a contact plate, with clamps in the corners.
CHARGING_STATION_TOP = framed([
    "761111111167",
    "612222222216",
    "1222TTTT2221",
    "122T0000T221",
    "12T0y66x0T21",
    "12T0x65x0T21",
    "12T0x54x0T21",
    "12T0x44x0T21",
    "122T0000T221",
    "1222TTTT2221",
    "612222222216",
    "761222222167",
])
# While a drone is docked: the ring and the plate light up.
CHARGING_STATION_TOP_WORKING = lit(
    CHARGING_STATION_TOP, (4, 4, 11, 11), {"T": "t", "x": "g", "y": "G", "6": "H", "5": "G", "4": "g"})

# Deploying Station top: looking down the open launch shaft, its walls darker toward the pad at the bottom, with rail
# lights dimming as they descend, inside a hazard-striped rim.
DEPLOYING_STATION_TOP = framed([
    "YY00YY00YY00",
    "Yt44444444tZ",
    "04T333333T5Z",
    "043e1111e450",
    "Y43110011450",
    "Y4310350145Z",
    "04310530145Z",
    "043110011450",
    "Y43e1111e450",
    "Y4T444444T5Z",
    "0t55555555tZ",
    "0ZZ00ZZ00ZZ0",
])

# Deploying Station front: the launch button under a hinged glass cover, the Auto/Manual lever and a redstone port,
# which lights up while the station is powered (the `triggered` state).
DEPLOYING_STATION_FRONT = framed([
    "YY00YY00YY00",
    "Z00ZZ00ZZ00Z",
    "122222222222",
    "120555555022",
    "120y1111x022",
    "120y1qq1x022",
    "120xqRSqx022",
    "120xqRRqx022",
    "120x1qq1x022",
    "120000000022",
    "108702220p02",
    "100002220002",
])
DEPLOYING_STATION_FRONT_TRIGGERED = lit(DEPLOYING_STATION_FRONT, (2, 2, 13, 13), {"p": "P"})

# Deploying Station sides: guide rails with two up-chevron lights between them. The bottom chevron lights up while a
# drone is in the slot (`shaft=loaded`); while launching, light sweeps quickly up both (animated).
DEPLOYING_STATION_SIDE = framed([
    "YY00YY00YY00",
    "Z00ZZ00ZZ00Z",
    "143111111432",
    "14311TT11432",
    "1431T00T1432",
    "143T0110T432",
    "143111111432",
    "14311TT11432",
    "1431T00T1432",
    "143T0110T432",
    "143111111432",
    "143111111432",
])
DEPLOYING_STATION_UPPER_CHEVRON = (5, 5, 10, 7)
DEPLOYING_STATION_LOWER_CHEVRON = (5, 9, 10, 11)
DEPLOYING_STATION_SIDE_LOADED = lit(DEPLOYING_STATION_SIDE, DEPLOYING_STATION_LOWER_CHEVRON, {"T": "t"})


def deploying_station_side_launching():
    """Bright light runs from the bottom chevron to the top one and fades, one game tick per frame."""
    out = []
    for lower, upper in (("K", "T"), ("t", "K"), ("T", "t"), ("T", "T")):
        rows = lit(lit(DEPLOYING_STATION_SIDE, DEPLOYING_STATION_LOWER_CHEVRON, {"T": lower}),
                   DEPLOYING_STATION_UPPER_CHEVRON, {"T": upper})
        out.append(machine(rows))
    return out


# Deploying Station back: a glass window into the launch shaft, with the rail lights and the pad at the bottom.
DEPLOYING_STATION_BACK = framed([
    "YY00YY00YY00",
    "00ZZ00ZZ00ZZ",
    "120000000022",
    "120x0xx0x022",
    "120T0yx0T022",
    "120x0xy0x022",
    "120T0xx0T022",
    "120x0xx0x022",
    "120T0xx0T022",
    "120x0xx0x022",
    "120566665022",
    "120000000022",
])

# Drone Factory front: a viewport into the assembly bay with the drone seen from above (rotor rings at the corners,
# arms, the body with its canopy, the lens at the front, which is the bottom), and the status light.
DRONE_FACTORY_BAY = (3, 3)
DRONE_FACTORY_DRONE = [  # "." is glass
    ".66....66.",
    "6xx6..6xx6",
    "6xx6..6xx6",
    ".66555566.",
    "...5BB5...",
    "...5BB5...",
    ".66555566.",
    "6xx6tt6xx6",
    "6xx6..6xx6",
    ".66....66.",
]
DRONE_FACTORY_VIEWPORT = framed([
    "111111111111",
    "1yxxxxxxxxx2",
    "1xyxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "1xxxxxxxxxx2",
    "134432222ij2",
])


def drone_factory_bay(rows, keys):
    """Draws the drone's pixels into the bay as keys(x, y, key); None leaves the pixel as it is."""
    rows = [list(row) for row in rows]
    bx, by = DRONE_FACTORY_BAY
    for y, row in enumerate(DRONE_FACTORY_DRONE):
        for x, key in enumerate(row):
            key = keys(x, y, key) if key != "." else None
            if key:
                rows[by + y][bx + x] = key
    return ["".join(row) for row in rows]


DRONE_FACTORY_FRONT = drone_factory_bay(DRONE_FACTORY_VIEWPORT, lambda x, y, key: key)


def drone_factory_front_working():
    """While building, a drone is made in a loop: a scan line draws it as a flickering teal hologram, metal fills it in
    from the body outward, the canopy takes its color, the lens lights and the rotors spin (each the opposite way to
    its neighbors), then it breaks up and the bay is empty again."""
    rng = random.Random(9)
    empty = lit(DRONE_FACTORY_VIEWPORT, (2, 2, 13, 13), {"i": "H", "j": "G"})
    built = lit(DRONE_FACTORY_FRONT, (2, 2, 13, 13), {"i": "H", "j": "G"})
    bx, by = DRONE_FACTORY_BAY

    def holo(x, y, key):
        return None if key == "x" else "T" if rng.random() > 0.2 else "t"

    frames = [empty] * 3
    # the scan line sweeps down, leaving the hologram above it
    for line in range(10):
        rows = [list(row) for row in drone_factory_bay(empty, lambda x, y, key: holo(x, y, key) if y < line else None)]
        for x in range(10):
            rows[by + line][bx + x] = "K" if DRONE_FACTORY_DRONE[line][x] not in ".x" else "t"
        frames.append(["".join(row) for row in rows])
    frames += [drone_factory_bay(empty, holo) for _ in range(3)]
    # metal fills it in from the body outward; the canopy and lens stay dark until it powers up
    for reach in (1.2, 2.4, 3.6, 4.8, 6.0):
        frames.append(drone_factory_bay(empty, lambda x, y, key: (
            {"B": "N", "t": "T"}.get(key, key) if math.hypot(x - 4.5, y - 4.5) < reach else holo(x, y, key))))
    # it powers up and the rotors spin: the blades alternate diagonals in each well, and a glint runs around the ring
    wells = [(1, 1), (7, 1), (1, 7), (7, 7)]
    ring = [(0, -1), (1, -1), (2, 0), (2, 1), (1, 2), (0, 2), (-1, 1), (-1, 0)]  # around a well, clockwise
    for f in range(10):
        rows = [list(row) for row in built]
        if f == 0:
            for x, y in ((4, 4), (5, 4), (4, 5), (5, 5)):
                rows[by + y][bx + x] = "N"
            rows[by + 7][bx + 4] = rows[by + 7][bx + 5] = "K"
        for i, (wx, wy) in enumerate(wells):
            spin = f if i in (0, 3) else -f
            for dx in (0, 1):
                for dy in (0, 1):
                    rows[by + wy + dy][bx + wx + dx] = "6" if (dx == dy) == (spin % 2 == 0) else "x"
            hx, hy = ring[spin % 8]
            rows[by + wy + hy][bx + wx + hx] = "8"
        frames.append(["".join(row) for row in rows])
    # it breaks up
    for p in (0.3, 0.6, 0.9):
        frames.append(drone_factory_bay(built, lambda x, y, key: (
            ("T" if rng.random() < 0.5 else "x") if rng.random() < p else None)))
    return [machine(rows) for rows in frames]


# Drone Factory top: a blueprint screen, the drone from above in thin lines over a dotted grid.
DRONE_FACTORY_TOP = framed([
    "111111111111",
    "1nNnnNnnNnn2",
    "1NBBnnnnBBN2",
    "1nBnBnnBnBn2",
    "1NnBNBBNBnN2",
    "1nnnBnnBnnn2",
    "1NnnBnnBnnN2",
    "1nnBNBBNBnn2",
    "1nBnBnnBnBn2",
    "1NBBnnnnBBN2",
    "1nNnnNnnNnn2",
    "122222222222",
])


def drone_factory_top_working(frames=24):
    """A soft glow runs out along the drone's lines from the body to the rotors, one shade brighter; the grid stays."""
    out = []
    for f in range(frames):
        rows = ["".join("o" if key == "B" and (math.hypot(x - 7.5, y - 7.5) / 6.4 - f / frames) % 1.0 < 0.18 else key
                        for x, key in enumerate(row)) for y, row in enumerate(DRONE_FACTORY_TOP)]
        out.append(machine(rows))
    return out


# Drone Factory back: the parts intake, a slatted grate with copper corners.
DRONE_FACTORY_BACK = framed([
    "111111111111",
    "1b77777777a2",
    "170000000052",
    "170434343052",
    "170000000052",
    "170434343052",
    "170000000052",
    "170434343052",
    "170000000052",
    "170434343052",
    "1a55555555a2",
    "122222222222",
])


def drone_factory_drum(offset=0):
    """The left side (seen from the front): a toothed drum seen side-on between its axle caps, shaded as a cylinder lit
    from the left. Its ridges are moved down `offset` pixels: moving in a straight line keeps them exact on the grid,
    where a turning cog can't keep its shape."""
    shades = "455667765544"
    rows = ["100087780002"]
    for y in range(1, 11):
        keys = []
        for x in range(12):
            if x in (0, 11):
                key = "1" if x == 0 else "2"
            elif x in (1, 10):
                key = "0"
            elif (y - offset) % 4 == 0:
                key = str(int(shades[x]) - 3)  # a ridge
            elif (y - offset) % 4 == 1:
                key = str(int(shades[x]) + 1)  # its lit edge
            else:
                key = shades[x]
            keys.append(key)
        rows.append("".join(keys))
    rows.append("100054450002")
    return framed(rows)


def drone_factory_fan(phase=0.0):
    """The right side (seen from the front): an exhaust fan in a round rim. Each blade is a thin line traced along a
    fixed curve, so it keeps its shape at any angle, with a dim trail behind it."""
    c = 6.0
    rows = []
    for y in range(12):
        keys = []
        for x in range(12):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy)
            if r > 5.9:
                key = "1" if x == 0 or y == 0 else "2"
            elif r > 4.9:
                key = "7" if dx + dy < -2 else "4" if dx + dy > 2 else "5"
            else:
                key = "0"
            keys.append(key)
        rows.append(keys)
    for trail, key in ((0.32, "2"), (0.0, "5")):
        for blade in range(4):
            for i in range(40):
                r = 1.3 + 3.5 * i / 39
                a = phase + blade * math.pi / 2 + 0.32 * r - trail
                x, y = int(c + r * math.cos(a)), int(c + r * math.sin(a))
                if math.hypot(x + 0.5 - c, y + 0.5 - c) < 4.9:
                    rows[y][x] = key
    for y in range(12):
        for x in range(12):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            if math.hypot(dx, dy) < 1.5:
                rows[y][x] = "8" if dx < 0 and dy < 0 else "6"
    return framed(["".join(row) for row in rows])


# Programming Station front: a terminal with lines of code and a cursor, the Direct/Template switch (blue, the lever,
# amber) and the status light.
PROGRAMMING_STATION_FRONT = framed([
    "111111111111",
    "100000000002",
    "10yxxxxxxx02",
    "10xTTxTTTx02",
    "10xxxxxxxx02",
    "10xxTTTxTx02",
    "10xxxxxxxx02",
    "10xtxxxxxx02",
    "100000000002",
    "122222222222",
    "10D87A002ij2",
    "122222222222",
])

# Programming Station left side (seen from the front): the upgrade input, a 3x3 bay of cards in their slots, each
# with a label in its upgrade's color.
PROGRAMMING_STATION_CARDS = framed([
    "111111111111",
    "100000000002",
    "10ZZ0qq0BB02",
    "106506506502",
    "100000000002",
    "10gg0RR0ww02",
    "106506506502",
    "100000000002",
    "10TT0ZZ0gg02",
    "106506506502",
    "100000000002",
    "122222222ij2",
])

# Programming Station right side: a console showing the GUI's drone bay (the drone on its patrol ring), the
# Direct/Template rocker and a row of upgrade pips.
PROGRAMMING_STATION_CONSOLE = framed([
    "111111111111",
    "100000000222",
    "10yxTTxx0222",
    "10xTxxTx0D22",
    "10TxxxxK0822",
    "10TxxxxK0722",
    "10xTxxTx0A22",
    "10xxTTxx0222",
    "100000000222",
    "122222222222",
    "10i0i0i0i0i2",
    "122222222222",
])

# Programming Station back: a heatsink, vertical fins with a copper heat pipe across them.
PROGRAMMING_STATION_BACK = framed([
    "111111111111",
    "160606060602",
    "150505050502",
    "150505050502",
    "1cbcbcbcbcb2",
    "1ababababab2",
    "150505050502",
    "150505050502",
    "150505050502",
    "140404040402",
    "100000000002",
    "122222222222",
])


def programming_station_dish():
    """The top: an uplink dish in a round rim, lit from the top left, with four struts and a redstone emitter."""
    c = 6.0
    rows = []
    for y in range(12):
        keys = []
        for x in range(12):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy)
            if r > 5.6:
                key = "1" if x == 0 or y == 0 else "2"
            elif r > 4.7:
                key = "7" if dx + dy < -2 else "3" if dx + dy > 2 else "6"
            elif r > 4.0:
                key = "0"
            elif r < 1.0:
                key = "p"
            elif r < 1.8:
                key = "0"
            elif abs(dx) < 0.6 or abs(dy) < 0.6:
                key = "1"  # a strut
            else:
                key = "5" if dx + dy < -1 else "3" if dx + dy > 2 else "4"
            keys.append(key)
        rows.append("".join(keys))
    return framed(rows)


PROGRAMMING_STATION_TOP = programming_station_dish()


def programming_station_working(frames=48, beat=16):
    """While installing, every face plays one shared loop of three install beats, so the faces stay in step (animated
    textures run on a global clock and start together). In each beat a card on the left glows and sinks into its
    slot, the dish's emitter flares and a teal ring ripples out, the console's next pip lights, a warm glint runs
    along the heat pipe, and the terminal types a line. The drone circles its ring once per beat. The cards and pips
    reset at the end of the loop, so they never stand for a real count."""

    def faces(f):
        k, b = divmod(f, beat)
        return {"front": front(k, b), "cards": cards(k, b, f), "console": console(k, b, f), "back": back(b),
                "top": top(b)}

    def edit(rows):
        return [list(row) for row in rows]

    def put(rows, x, y, key):
        rows[y + 2][x + 2] = key  # inner grid coordinates

    def front(k, b):
        lines = ["TTxTTTx", "xTTTxTx", "TTTTxxx"]
        rows = edit(PROGRAMMING_STATION_FRONT)
        put(rows, 9, 10, "H")
        put(rows, 10, 10, "G")
        for y in range(3, 8):
            for x in range(3, 10):
                put(rows, x, y, "x")
        # the line being typed is at the bottom, the two before it scroll up
        typed = min(7, b)
        for line, n, y in ((lines[(k - 2) % 3], 7, 3), (lines[(k - 1) % 3], 7, 5), (lines[k], typed, 7)):
            for i in range(n):
                put(rows, 3 + i, y, line[i])
        if typed < 7 or b % 4 < 2:
            put(rows, 3 + typed if typed < 7 else 9, 7, "t")
        return rows

    def cards(k, b, f):
        order = [(0, 0), (2, 1), (1, 2)]  # (column, row) of the card installed in each beat
        brighter = {"Z": "Y", "q": "R", "B": "o", "g": "G", "R": "S", "w": "m", "T": "t"}
        rows = edit(PROGRAMMING_STATION_CARDS)
        for i, (cx, cy) in enumerate(order):
            x, y = 2 + 3 * cx, 2 + 3 * cy
            sunk = (i < k or (i == k and b >= 6)) and f < frames - 2
            for dx in (0, 1):
                key = rows[y + 2][x + dx + 2]
                if sunk:
                    put(rows, x + dx, y, "0")
                elif i == k and b < 6:
                    put(rows, x + dx, y, brighter.get(key, key))
        return rows

    def console(k, b, f):
        rows = edit(PROGRAMMING_STATION_CONSOLE)
        ring = sorted(((x, y) for y in range(2, 8) for x in range(2, 8) if rows[y + 2][x + 2] in "TK"),
                      key=lambda p: math.atan2(p[1] - 4.5, p[0] - 4.5))
        for x, y in ring:
            put(rows, x, y, "T")
        pos = f * len(ring) / beat
        for behind, key in ((2, "t"), (1, "t"), (0, "K")):
            put(rows, *ring[int(pos - behind) % len(ring)], key)
        for i in range(3):
            if i < k or (i == k and b >= 7):
                put(rows, 2 + 2 * i, 10, "G")
        return rows

    def back(b):
        warmer = {"a": "b", "b": "c", "c": "d"}
        rows = edit(PROGRAMMING_STATION_BACK)
        for y in (4, 5):
            for x in range(12):
                key = rows[y + 2][x + 2]
                d = x - (b - 6) * 1.6
                if key in warmer and b >= 6 and -2.5 < d <= 0.5:
                    put(rows, x, y, warmer[warmer[key]] if d > -1 and key != "c" else warmer[key])
        return rows

    def top(b):
        lighter = {"3": "4", "4": "5", "5": "6", "6": "7"}
        rows = edit(PROGRAMMING_STATION_TOP)
        edge = 1.6 + (b - 6) * 0.45
        for y in range(12):
            for x in range(12):
                r = math.hypot(x + 0.5 - 6, y + 0.5 - 6)
                key = rows[y + 2][x + 2]
                if key == "p":
                    put(rows, x, y, "S" if b in (6, 7) else "P")
                elif key == "0" and r < 1.8 and b in (6, 7, 8):
                    put(rows, x, y, "q")
                elif 6 <= b <= 13 and key in "345":
                    if abs(r - edge) < 0.55:
                        put(rows, x, y, "t" if b < 9 else "T" if b < 12 else lighter[key])
                    elif 0 < edge - r < 1.0 and b < 12:
                        put(rows, x, y, lighter[lighter[key]] if b < 10 else lighter[key])
        return rows

    out = {}
    for f in range(frames):
        for face, rows in faces(f).items():
            out.setdefault(face, []).append(machine(["".join(row) for row in rows]))
    return out


MACHINES = {
    "machine_bottom": MACHINE_BOTTOM,
    "charging_station_side": CHARGING_STATION_SIDE,
    "charging_station_side_working": CHARGING_STATION_SIDE_WORKING,
    "charging_station_front": CHARGING_STATION_FRONT,
    "charging_station_back": CHARGING_STATION_BACK,
    "charging_station_back_working": CHARGING_STATION_BACK_WORKING,
    "charging_station_top": CHARGING_STATION_TOP,
    "charging_station_top_working": CHARGING_STATION_TOP_WORKING,
    "deploying_station_top": DEPLOYING_STATION_TOP,
    "deploying_station_front": DEPLOYING_STATION_FRONT,
    "deploying_station_front_triggered": DEPLOYING_STATION_FRONT_TRIGGERED,
    "deploying_station_side": DEPLOYING_STATION_SIDE,
    "deploying_station_side_loaded": DEPLOYING_STATION_SIDE_LOADED,
    "deploying_station_back": DEPLOYING_STATION_BACK,
    "drone_factory_front": DRONE_FACTORY_FRONT,
    "drone_factory_top": DRONE_FACTORY_TOP,
    "drone_factory_back": DRONE_FACTORY_BACK,
    "drone_factory_drum": drone_factory_drum(),
    "drone_factory_fan": drone_factory_fan(),
    "programming_station_front": PROGRAMMING_STATION_FRONT,
    "programming_station_cards": PROGRAMMING_STATION_CARDS,
    "programming_station_console": PROGRAMMING_STATION_CONSOLE,
    "programming_station_back": PROGRAMMING_STATION_BACK,
    "programming_station_top": PROGRAMMING_STATION_TOP,
}
# Animated faces: name -> (frames, game ticks per frame).
ANIMATED_MACHINES = {
    "charging_station_front_working": (charging_station_front_working(), 2),
    "deploying_station_side_launching": (deploying_station_side_launching(), 1),
    "drone_factory_front_working": (drone_factory_front_working(), 2),
    "drone_factory_top_working": (drone_factory_top_working(), 2),
    # The drum moves one ridge per loop, the fan a quarter turn (one blade).
    "drone_factory_drum_working": ([machine(drone_factory_drum(f)) for f in range(4)], 2),
    "drone_factory_fan_working": ([machine(drone_factory_fan(-math.pi / 2 * f / 6)) for f in range(6)], 1),
    **{f"programming_station_{face}_working": (frames, 2) for face, frames in programming_station_working().items()},
}


def outputs():
    textures = {
        "entity/drone.png": drone_entity(),
        "item/drone.png": drone_item(),
        "item/drone_tint.png": grid(DRONE_ITEM_TINT),
        "item/drone_rotor.png": drone_rotor(),
        "item/seeker_core.png": seeker_core(),
    }
    for name in UPGRADES:
        textures[f"item/{name}_upgrade.png"] = upgrade(name)
    for name, rows in MACHINES.items():
        textures[f"block/{name}.png"] = machine(rows)
    for name, (frames, frametime) in ANIMATED_MACHINES.items():
        textures[f"block/{name}.png"] = animation(frames, frametime)
    return textures


def animation(frames, frametime):
    """Stacks the frames vertically, as Minecraft expects, and records the .mcmeta to write beside it."""
    strip = Image.new("RGBA", (16, 16 * len(frames)))
    for i, frame in enumerate(frames):
        strip.paste(frame, (0, 16 * i))
    strip.info["mcmeta"] = {"animation": {"frametime": frametime}}
    return strip


INVENTORY_SLOT = (139, 139, 139, 255)
# The hotbar's near-black slot (#040404 at 73% opacity) over grass.
HOTBAR_SLOT = (19, 27, 17, 255)


def contact_sheet(textures, scale=6):
    """Items on an inventory slot and on the hotbar (the drone in several colors), then the block faces."""
    items = [img for name, img in textures.items() if name.startswith("item/") and name != "item/drone_tint.png"]
    tint = textures["item/drone_tint.png"]
    for color in ((60, 68, 170), (180, 40, 40), (240, 240, 240), (29, 29, 33)):
        drone = textures["item/drone.png"].copy()
        layer = ImageChops.multiply(tint, Image.new("RGBA", tint.size, color + (255,)))
        layer.putalpha(tint.getchannel("A"))
        drone.alpha_composite(layer)
        items.append(drone)
    items = [img for img in items if img is not textures["item/drone.png"]]
    blocks = [img.crop((0, 0, 16, 16)) for name, img in textures.items() if name.startswith("block/")]

    cell = 20
    width = max(len(items), len(blocks)) * cell
    sheet = Image.new("RGBA", (width, cell * 3), (255, 255, 255, 255))
    for row, background in enumerate((INVENTORY_SLOT, HOTBAR_SLOT)):
        for i, img in enumerate(items):
            slot = Image.new("RGBA", (cell, cell), background)
            slot.alpha_composite(img, (2, 2))
            sheet.alpha_composite(slot, (i * cell, row * cell))
    for i, img in enumerate(blocks):
        sheet.alpha_composite(img, (i * cell + 2, 2 * cell + 2))
    return sheet.resize((sheet.width * scale, sheet.height * scale), Image.NEAREST)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--preview", type=Path, help="also write 16x upscaled copies to this directory")
    args = parser.parse_args()

    textures = outputs()
    for name, img in textures.items():
        path = TEXTURES / name
        path.parent.mkdir(parents=True, exist_ok=True)
        img.save(path)
        print(f"wrote {path.relative_to(ROOT)}")
        if "mcmeta" in img.info:
            path.with_name(path.name + ".mcmeta").write_text(json.dumps(img.info["mcmeta"], indent=2) + "\n")
        if args.preview:
            preview = args.preview / name
            preview.parent.mkdir(parents=True, exist_ok=True)
            img.resize((img.width * 16, img.height * 16), Image.NEAREST).save(preview)
    if args.preview:
        contact_sheet(textures).save(args.preview / "sheet.png")


if __name__ == "__main__":
    main()
