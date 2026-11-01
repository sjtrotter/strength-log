"""Check that committed Play listing images match the app's rendered images."""
import sys
from pathlib import Path

from PIL import Image, ImageChops


def check(rendered, committed, expected_size):
    try:
        with Image.open(rendered) as image:
            rgb = image.convert("RGB")
        with Image.open(committed) as image:
            committed_rgb = image.convert("RGB")
        if rgb.size != expected_size:
            raise ValueError(f"rendered size must be {expected_size[0]}x{expected_size[1]}")
        if rgb.size != committed_rgb.size:
            raise ValueError("sizes differ")
        if ImageChops.difference(rgb, committed_rgb).getbbox() is not None:
            raise ValueError("pixels differ")
        print(f"PASS {rendered.name} {rgb.width}x{rgb.height}")
        return True
    except (OSError, ValueError) as error:
        print(f"FAIL {rendered.name} {error}")
        return False


def main():
    if len(sys.argv) != 3:
        print("Usage: check_listing_images.py <rendered_dir> <committed_dir>")
        return 1
    rendered_dir, committed_dir = map(Path, sys.argv[1:])
    passed = True
    for name, size in (("icon.png", (512, 512)), ("featureGraphic.png", (1024, 500))):
        if not check(rendered_dir / name, committed_dir / name, size):
            passed = False
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
