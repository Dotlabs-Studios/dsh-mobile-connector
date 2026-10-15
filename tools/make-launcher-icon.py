"""Build the Android launcher icon from the harness's own app icon.

The app is a client for DeepSeek Harness, so it should carry the harness's mark
rather than an invented one. The source is the `icon.png` the installed harness
ships for its own window and taskbar entry: a dark whale on a near-white rounded
plate, drawn on transparency.

An Android adaptive icon is not one image. The launcher supplies the shape and
masks whatever it is given, and only the middle 72 of 108 units are guaranteed to
survive that masking — so a square icon dropped in as-is gets its corners, and
with them most of the mark, cut off. What is needed is a *foreground* holding
only the whale, centred inside that safe zone, with transparency everywhere else,
plus a *background* colour for the launcher to fill the rest with.

The whale has to be lifted off its plate to get that, and the plate is also the
key: it is the brightest thing in the image and the whale is the darkest, so the
whale's alpha is how far each pixel has travelled from one toward the other. That
keeps the anti-aliased edges smooth, which a threshold would turn into a
staircase. The image's own alpha is multiplied in, so nothing outside the rounded
plate survives.

Usage:
    python tools/make-launcher-icon.py [path-to-harness-icon.png]
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from PIL import Image

#: Where the installed harness keeps its own icon, when no path is given: the
#: per-user install location the Windows installer uses. A portable or
#: system-wide install is not there, and takes the path as an argument.
DEFAULT_SOURCE = (
    Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    / "Programs"
    / "DeepSeek Harness"
    / "resources"
    / "icon.png"
)

#: The Android resource tree this writes into.
RES = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "res"

#: Adaptive icons are laid out on a 108-unit square, of which the middle 72 are
#: always visible. The mark is sized to 62 units so it also clears the tighter
#: circular masks some launchers apply.
CANVAS = 108
MARK = 62

#: One entry per density bucket Android asks for.
DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

#: Alpha below this counts as background when measuring the mark's bounds.
ALPHA_FLOOR = 12

#: Alpha below this is part of the transparent margin rather than the plate.
OPAQUE = 200

#: How dark a pixel must be before it counts as the mark at all.
#:
#: The plate is not one flat colour — it shades from #FCFCFC at the top to a
#: slightly darker grey at the bottom edge — so measuring from the brightest
#: pixel alone leaves a faint square of its own shading behind. A floor discards
#: that, at the cost of the outermost anti-aliasing ring, which is invisible.
KEY_FLOOR = 0.14


def channel_mean(pixel: tuple[int, int, int]) -> float:
    """The perceived lightness of one pixel.

    @param pixel the RGB triple.
    @returns 0 for black, 255 for white.
    """
    red, green, blue = pixel[:3]
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def to_hex(pixel: tuple[int, int, int]) -> str:
    """Render a colour the way a resource file wants it.

    @param pixel the RGB triple.
    @returns the colour as `#rrggbb`.
    """
    return "#{:02X}{:02X}{:02X}".format(*pixel[:3])


def build_mark(source: Image.Image) -> tuple[Image.Image, tuple[int, int, int], tuple[int, int, int]]:
    """Lift the whale off its plate.

    @param source the harness icon, in RGBA.
    @returns the RGBA mark, the plate colour, and the whale's colour.
    """
    width, height = source.size
    pixels = list(source.getdata())
    solid = [pixel for pixel in pixels if pixel[3] >= OPAQUE]
    if not solid:
        raise SystemExit("this icon is entirely transparent; nothing to lift out")
    plate = max(solid, key=channel_mean)
    whale = min(solid, key=channel_mean)
    plate_light = channel_mean(plate)
    whale_light = channel_mean(whale)
    span = max(1.0, plate_light - whale_light)

    out = Image.new("RGBA", (width, height))
    out.putdata(
        [
            (
                whale[0],
                whale[1],
                whale[2],
                round(
                    255
                    * max(
                        0.0,
                        (
                            (plate_light - channel_mean(pixel)) / span - KEY_FLOOR
                        )
                        / (1.0 - KEY_FLOOR),
                    )
                    * (pixel[3] / 255.0)
                ),
            )
            for pixel in pixels
        ]
    )
    return out, plate, whale


def trimmed(mark: Image.Image) -> Image.Image:
    """Crop the mark to the pixels that are actually visible.

    @param mark the full-size RGBA mark.
    @returns the mark cropped to its own bounds.
    """
    alpha = mark.getchannel("A").point(lambda value: 255 if value > ALPHA_FLOOR else 0)
    box = alpha.getbbox()
    return mark.crop(box) if box else mark


def foreground(mark: Image.Image, size: int) -> Image.Image:
    """Draw the mark centred on a transparent adaptive-icon canvas.

    @param mark the cropped RGBA mark.
    @param size the canvas edge in pixels.
    @returns the foreground layer.
    """
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = round(size * MARK / CANVAS)
    scale = target / max(mark.width, mark.height)
    scaled = mark.resize(
        (max(1, round(mark.width * scale)), max(1, round(mark.height * scale))),
        Image.LANCZOS,
    )
    canvas.paste(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2), scaled)
    return canvas


def main() -> int:
    """Generate every density of the launcher foreground.

    @returns a process exit code.
    """
    source_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    if not source_path.exists():
        print(f"source icon not found: {source_path}")
        return 2

    source = Image.open(source_path).convert("RGBA")
    mark, plate, whale = build_mark(source)
    cropped = trimmed(mark)
    print(f"source {source.size[0]}x{source.size[1]}")
    print(f"plate {to_hex(plate)}   whale {to_hex(whale)}   mark bounds {cropped.size[0]}x{cropped.size[1]}")

    for name, size in DENSITIES.items():
        directory = RES / f"drawable-{name}"
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / "ic_launcher_foreground.png"
        foreground(cropped, size).save(target, "PNG", optimize=True)
        print(f"  drawable-{name}/ic_launcher_foreground.png  {size}x{size}")

    print(f"background colour for the adaptive icon: {to_hex(plate)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
