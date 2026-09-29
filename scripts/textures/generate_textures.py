"""Generates the mod's placeholder textures (until the commissioned art replaces them).

Everything is drawn from scratch with a shared palette; no vanilla textures are copied. Run from anywhere:

    python scripts/textures/generate_textures.py [--preview DIR]

Requires Pillow. The PNGs are committed, so the build doesn't depend on this script. --preview also writes 16x
upscaled copies to DIR for checking the result, plus sheet.png: every item on an inventory slot and on the
hotbar (icons must read on both), and the block faces.
"""

import argparse
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
    "xray": {
        # A wave passing through a wall.
        "colors": {"1": (18, 28, 44), "2": (160, 255, 130), "3": (112, 122, 134)},
        "symbol": [
            "...33...",
            "...33...",
            ".2233...",
            "2..23...",
            "...32..2",
            "...3322.",
            "...33...",
            "...33...",
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
    "multi_target": {
        "colors": {"1": (18, 96, 96), "2": (124, 232, 220), "3": (230, 255, 252)},
        "symbol": [
            "...2....",
            "..232...",
            "...2....",
            "........",
            "........",
            ".2....2.",
            "232..232",
            ".2....2.",
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

# Every machine face is the same casing: a mid-metal edge, a light frame with corner bolts, and a recessed dark
# panel. Only the 8x8 symbol inside the panel changes, so the machines read as one family. In the symbols, "."
# is the panel color.


def machine_face(symbol):
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                key = "m"
            elif (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
                key = "W"
            elif x in (1, 2, 13, 14) or y in (1, 2, 13, 14):
                key = "L" if x == 1 or y == 1 else "l"
            elif x == 3 or y == 3:
                key = "k"
            elif x == 12 or y == 12:
                key = "m"
            else:
                key = symbol[y - 4][x - 4]
                key = "d" if key == "." else key
            row.append(key)
        rows.append("".join(row))
    return grid(rows)


MACHINE_SYMBOLS = {
    # The bottom of every machine: a vent grille.
    "machine_bottom": [
        "hhhhhhhh",
        "........",
        "hhhhhhhh",
        "........",
        "hhhhhhhh",
        "........",
        "hhhhhhhh",
        "........",
    ],
    # Charging Station: a battery on the sides, a charging pad on top.
    "charging_station_side": [
        "...ll...",
        "..llll..",
        "..lnnl..",
        "..leel..",
        "..lEel..",
        "..leel..",
        "..leel..",
        "..llll..",
    ],
    "charging_station_top": [
        "........",
        ".eeeeee.",
        ".e....e.",
        ".e.EE.e.",
        ".e.EE.e.",
        ".e....e.",
        ".eeeeee.",
        "........",
    ],
    # Deploying Station: launch chevrons on the sides, an open hatch on top.
    "deploying_station_side": [
        "........",
        "...ee...",
        "..eEEe..",
        ".ee..ee.",
        "...ee...",
        "..eEEe..",
        ".ee..ee.",
        "........",
    ],
    "deploying_station_top": [
        "E......E",
        ".hhhhhh.",
        ".hkkkkh.",
        ".hkkkkh.",
        ".hkkkkh.",
        ".hkkkkh.",
        ".hhhhhh.",
        "E......E",
    ],
    # Drone Factory: a gear on the sides, an assembly grid on top.
    "drone_factory_side": [
        "...ll...",
        ".l.ll.l.",
        "..llll..",
        "lllhhlll",
        "lllhhlll",
        "..llll..",
        ".l.ll.l.",
        "...ll...",
    ],
    "drone_factory_top": [
        "ll.ll.ll",
        "lm.lm.lm",
        "........",
        "ll.eE.ll",
        "lm.ee.lm",
        "........",
        "ll.ll.ll",
        "lm.lm.lm",
    ],
    # Programming Station: a screen on the front, a keyboard on top, plain vents on the other sides.
    "programming_station_front": [
        "nnnnnnnn",
        "neeebenn",
        "nnnnnnnn",
        "nbbnbbbn",
        "nnnnnnnn",
        "neebnnnn",
        "nnnnnnnn",
        "nEnnnnnn",
    ],
    "programming_station_top": [
        "........",
        ".lmlmlm.",
        "........",
        ".mlmlml.",
        "........",
        ".lmmmml.",
        "........",
        "........",
    ],
    "programming_station_side": [
        "........",
        "mmmmmmmm",
        "........",
        "mmmmmmmm",
        "........",
        "mmmmmmmm",
        "........",
        "mmmmmmmm",
    ],
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
    for name, symbol in MACHINE_SYMBOLS.items():
        textures[f"block/{name}.png"] = machine_face(symbol)
    return textures


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
    blocks = [img for name, img in textures.items() if name.startswith("block/")]

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
        if args.preview:
            preview = args.preview / name
            preview.parent.mkdir(parents=True, exist_ok=True)
            img.resize((img.width * 16, img.height * 16), Image.NEAREST).save(preview)
    if args.preview:
        contact_sheet(textures).save(args.preview / "sheet.png")


if __name__ == "__main__":
    main()
